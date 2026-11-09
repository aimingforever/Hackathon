package com.cnh.goodfish.question;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import java.io.IOException;
import java.io.StringReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Creates the question and board indices when the application starts. */
@Component
@ConditionalOnProperty(name = "goodfish.questions.create-index", havingValue = "true", matchIfMissing = true)
class QuestionIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QuestionIndexInitializer.class);

    private final ElasticsearchClient client;
    private final QuestionProperties properties;

    QuestionIndexInitializer(ElasticsearchClient client, QuestionProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        boolean questionIndexExisted = createIfMissing(properties.index(), questionMapping());
        createIfMissing(properties.boardIndex(), BOARD_MAPPING);
        if (questionIndexExisted) {
            backfillKeywords(properties.index());
        }
    }

    /** One-off, idempotent: documents indexed before "keywords" existed get it seeded from their variants. */
    private void backfillKeywords(String index) throws IOException {
        var response = client.updateByQuery(u -> u.index(index)
                .conflicts(Conflicts.Proceed)
                .refresh(true)
                .withJson(new StringReader(BACKFILL_KEYWORDS_REQUEST)));
        log.info("Backfilled keywords on {} documents in '{}'", response.updated(), index);
    }

    /** Returns true if the index already existed. */
    private boolean createIfMissing(String index, String mapping) throws IOException {
        if (client.indices().exists(e -> e.index(index)).value()) {
            // Adding new fields to an existing mapping is allowed and idempotent.
            client.indices().putMapping(m -> m.index(index).withJson(new StringReader(propertiesOnly(mapping))));
            log.info("Index '{}' already exists; mapping updated", index);
            return true;
        }
        client.indices().create(c -> c.index(index).withJson(new StringReader(mapping)));
        log.info("Created index '{}'", index);
        return false;
    }

    static final String BACKFILL_KEYWORDS_REQUEST = """
            {
              "query": { "bool": { "must_not": { "exists": { "field": "keywords" } } } },
              "script": {
                "lang": "painless",
                "source": "if (ctx._source.variants != null) { ctx._source.keywords = new ArrayList(ctx._source.variants); }"
              }
            }
            """;
    /** Turns {"mappings": {...}} into the {...} body that the put-mapping API expects. */
    static String propertiesOnly(String mapping) {
        String body = mapping.strip();
        int start = body.indexOf('{', body.indexOf("\"mappings\""));
        int end = body.lastIndexOf('}', body.lastIndexOf('}') - 1);
        return body.substring(start, end + 1);
    }

    String questionMapping() {
        return """
                {
                  "mappings": {
                    "properties": {
                      "id":            { "type": "keyword" },
                      "board":         { "type": "keyword" },
                      "text":          { "type": "semantic_text", "inference_id": "%s" },
                      "variants":      { "type": "semantic_text", "inference_id": "%s" },
                      "variantTokens": { "type": "object", "enabled": false },
                      "keywords":      { "type": "text", "analyzer": "english" },
                      "asks":          { "type": "integer" },
                      "votes":         { "type": "integer" },
                      "popularity":    { "type": "integer" },
                      "createdAt":     { "type": "date", "format": "epoch_millis" },
                      "updatedAt":     { "type": "date", "format": "epoch_millis" }
                    }
                  }
                }
                """.formatted(properties.inferenceId(), properties.inferenceId());
    }

    static final String BOARD_MAPPING = """
            {
              "mappings": {
                "properties": {
                  "board":       { "type": "keyword" },
                  "topic":       { "type": "text" },
                  "topicTokens": { "type": "object", "enabled": false }
                }
              }
            }
            """;
}
