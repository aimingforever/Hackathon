package com.cnh.goodfish.question;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Stores questions and merges ones that mean the same thing.
 *
 * <p>Matching runs in two steps:
 * <ol>
 *   <li>Elasticsearch finds the closest existing groups on the board with an ELSER semantic query.</li>
 *   <li>Each candidate is compared with a topic-weighted cosine similarity over ELSER token vectors,
 *       so shared topic words (the talk subject) do not make different questions look identical.</li>
 * </ol>
 * If the best candidate reaches {@link QuestionProperties#mergeThreshold()} the question is merged
 * into it; otherwise it starts a new group.
 */
@Service
public class QuestionService {

    static final int MAX_VARIANTS = 20;

    private static final String MERGE_SCRIPT = """
            ctx._source.asks += 1;
            ctx._source.popularity += 1;
            ctx._source.updatedAt = params.now;
            if (ctx._source.variants.size() < params.maxVariants && !ctx._source.variants.contains(params.text)) {
              ctx._source.variants.add(params.text);
              ctx._source.variantTokens.add(params.tokens);
            }
            """;

    private static final String UPVOTE_SCRIPT = """
            ctx._source.votes += 1;
            ctx._source.popularity += 1;
            ctx._source.updatedAt = params.now;
            """;

    private final ElasticsearchClient client;
    private final QuestionProperties properties;
    private final Embedder embedder;
    private final BoardService boards;
    private final Clock clock;
    private final Map<String, Object> boardLocks = new ConcurrentHashMap<>();

    public QuestionService(ElasticsearchClient client, QuestionProperties properties, Embedder embedder,
                           BoardService boards, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.embedder = embedder;
        this.boards = boards;
        this.clock = clock;
    }

    /** Result of submitting a question: the group it ended up in and how similar the best match was. */
    public record SubmitResult(QuestionView group, boolean merged, Double similarity) {
    }

    /** A candidate group and its similarity to the submitted question. */
    record Match(String id, double similarity) {
    }

    /**
     * Submits a question. Submissions are serialized per board so that concurrent identical questions
     * cannot each miss the other and create duplicate groups.
     */
    public SubmitResult submit(String board, String text) {
        return withBoardLock(board, () -> doSubmit(board, text));
    }

    <T> T withBoardLock(String board, Supplier<T> action) {
        synchronized (boardLocks.computeIfAbsent(board, key -> new Object())) {
            return action.get();
        }
    }

    private SubmitResult doSubmit(String board, String text) {
        String question = text.strip();
        Map<String, Float> tokens = embedder.embed(question);
        Match best = bestMatch(candidates(board, question), tokens, boards.topicWeights(board));

        if (best != null && shouldMerge(best.similarity(), properties.mergeThreshold())) {
            QuestionGroup merged = update(best.id(), MERGE_SCRIPT, Map.of(
                    "text", JsonData.of(question),
                    "tokens", JsonData.of(tokens),
                    "maxVariants", JsonData.of(MAX_VARIANTS)));
            return new SubmitResult(QuestionView.of(merged), true, best.similarity());
        }

        QuestionGroup group = QuestionGroup.create(UUID.randomUUID().toString(), board, question, tokens, clock.millis());
        try {
            client.index(i -> i.index(properties.index()).id(group.id()).document(group).refresh(Refresh.WaitFor));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new SubmitResult(QuestionView.of(group), false, best == null ? null : best.similarity());
    }

    /** Deletes every question group on a board, e.g. to reset a demo board. Returns how many were deleted. */
    public long clear(String board) {
        return withBoardLock(board, () -> {
            try {
                return client.deleteByQuery(d -> d
                        .index(properties.index())
                        .query(q -> q.term(t -> t.field("board").value(board)))
                        .refresh(true)).deleted();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    public QuestionView upvote(String id) {
        return QuestionView.of(update(id, UPVOTE_SCRIPT, Map.of()));
    }

    public List<QuestionView> top(String board, int limit) {
        try {
            SearchResponse<QuestionGroup> response = client.search(s -> s
                            .index(properties.index())
                            .size(limit)
                            .source(src -> src.filter(f -> f.includes(QuestionGroup.SOURCE_FIELDS)))
                            .query(q -> q.term(t -> t.field("board").value(board)))
                            .sort(so -> so.field(f -> f.field("popularity").order(SortOrder.Desc)))
                            .sort(so -> so.field(f -> f.field("updatedAt").order(SortOrder.Desc))),
                    QuestionGroup.class);
            return response.hits().hits().stream().map(Hit::source).map(QuestionView::of).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean shouldMerge(Double similarity, double threshold) {
        return similarity != null && similarity >= threshold;
    }

    /** Picks the candidate whose closest variant is most similar to the new question. */
    static Match bestMatch(List<QuestionGroup> candidates, Map<String, Float> tokens, Map<String, Double> weights) {
        Match best = null;
        for (QuestionGroup group : candidates) {
            for (Map<String, Float> variant : group.variantTokens()) {
                double similarity = SparseVectors.cosine(tokens, variant, weights);
                if (best == null || similarity > best.similarity()) {
                    best = new Match(group.id(), similarity);
                }
            }
        }
        return best;
    }

    private List<QuestionGroup> candidates(String board, String question) {
        try {
            SearchResponse<QuestionGroup> response = client.search(s -> s
                            .index(properties.index())
                            .size(properties.candidates())
                            .source(src -> src.filter(f -> f.includes(QuestionGroup.SOURCE_FIELDS)))
                            .query(q -> q.bool(b -> b
                                    .filter(f -> f.term(t -> t.field("board").value(board)))
                                    .must(m -> m.bool(inner -> inner
                                            .should(sh -> sh.semantic(sem -> sem.field("text").query(question)))
                                            .should(sh -> sh.semantic(sem -> sem.field("variants").query(question)))
                                            .minimumShouldMatch("1"))))),
                    QuestionGroup.class);
            return response.hits().hits().stream().map(Hit::source).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private QuestionGroup update(String id, String script, Map<String, JsonData> extraParams) {
        Map<String, JsonData> params = new HashMap<>(extraParams);
        params.put("now", JsonData.of(clock.millis()));
        try {
            UpdateResponse<QuestionGroup> response = client.update(u -> u
                            .index(properties.index())
                            .id(id)
                            .retryOnConflict(5)
                            .refresh(Refresh.WaitFor)
                            .source(src -> src.filter(f -> f.includes(QuestionGroup.SOURCE_FIELDS)))
                            .script(sc -> sc.source(s -> s.scriptString(script)).params(params)),
                    QuestionGroup.class);
            return response.get().source();
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                throw new QuestionNotFoundException(id);
            }
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
