package com.cnh.goodfish.question;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Read-side features for speakers and organizers, built on Elasticsearch:
 * <ul>
 *   <li>{@link #search}: hybrid search. A keyword retriever (English analyzer, fuzzy) and a semantic
 *       retriever (ELSER) are fused with Reciprocal Rank Fusion, so "Postgres" finds "Should I run
 *       databases on Kubernetes?" and a typo like "kubernets secrets" still finds the secrets question.</li>
 *   <li>{@link #stats}: aggregations for the speaker board and dashboards.</li>
 * </ul>
 */
@Service
public class BoardInsightsService {

    private final ElasticsearchClient client;
    private final QuestionProperties properties;

    public BoardInsightsService(ElasticsearchClient client, QuestionProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** A search hit: the question group and its fused RRF score. */
    public record SearchHit(QuestionView group, double score) {
    }

    /** Board statistics computed with Elasticsearch aggregations. */
    public record BoardStats(long questions, long groups, long duplicatesMerged, double mergeRate,
                             long votes, long busiestGroupAsks, List<TimelinePoint> newGroupsPerMinute) {
    }

    public record TimelinePoint(long minute, long groups) {
    }

    public List<SearchHit> search(String board, String query, int limit) {
        String json = """
                {
                  "size": %d,
                  "_source": %s,
                  "retriever": {
                    "rrf": {
                      "retrievers": [
                        { "standard": { "query": { "bool": {
                            "filter": { "term": { "board": %s } },
                            "must": { "match": { "keywords": { "query": %s, "fuzziness": "AUTO" } } } } } } },
                        { "standard": { "query": { "bool": {
                            "filter": { "term": { "board": %s } },
                            "should": [
                              { "semantic": { "field": "text", "query": %s } },
                              { "semantic": { "field": "variants", "query": %s } }
                            ],
                            "minimum_should_match": 1 } } } }
                      ],
                      "rank_window_size": 50,
                      "rank_constant": 20
                    }
                  }
                }
                """.formatted(limit, json(QuestionGroup.SOURCE_FIELDS), json(board), json(query),
                json(board), json(query), json(query));
        SearchResponse<QuestionGroup> response = run(json, QuestionGroup.class);
        return response.hits().hits().stream()
                .map(hit -> new SearchHit(QuestionView.of(hit.source()), hit.score() == null ? 0 : hit.score()))
                .toList();
    }

    public BoardStats stats(String board) {
        String json = """
                {
                  "size": 0,
                  "query": { "term": { "board": %s } },
                  "aggs": {
                    "questions": { "sum": { "field": "asks" } },
                    "votes":     { "sum": { "field": "votes" } },
                    "groups":    { "value_count": { "field": "board" } },
                    "busiest":   { "max": { "field": "asks" } },
                    "timeline":  { "date_histogram": { "field": "createdAt", "fixed_interval": "1m", "min_doc_count": 1 } }
                  }
                }
                """.formatted(json(board));
        Map<String, Aggregate> aggs = run(json, Void.class).aggregations();
        long questions = toLong(aggs.get("questions").sum().value());
        long groups = toLong(aggs.get("groups").valueCount().value());
        Double busiest = aggs.get("busiest").max().value();
        List<TimelinePoint> timeline = aggs.get("timeline").dateHistogram().buckets().array().stream()
                .map(b -> new TimelinePoint(b.key(), b.docCount()))
                .toList();
        long merged = Math.max(0, questions - groups);
        return new BoardStats(questions, groups, merged, questions == 0 ? 0 : (double) merged / questions,
                toLong(aggs.get("votes").sum().value()),
                busiest == null || busiest.isInfinite() ? 0 : busiest.longValue(), timeline);
    }

    private <T> SearchResponse<T> run(String json, Class<T> type) {
        try {
            SearchRequest request = SearchRequest.of(b -> b.index(properties.index()).withJson(new StringReader(json)));
            return client.search(request, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long toLong(Double value) {
        return value == null || value.isInfinite() || value.isNaN() ? 0 : value.longValue();
    }

    /** JSON-encodes a string or a list of strings for safe embedding in the query templates above. */
    static String json(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(BoardInsightsService::json).collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        String text = String.valueOf(value);
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
