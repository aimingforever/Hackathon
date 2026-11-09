package com.cnh.goodfish.question;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
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
        createIfMissing(properties.index(), questionMapping());
        createIfMissing(properties.boardIndex(), BOARD_MAPPING);
    }

    private void createIfMissing(String index, String mapping) throws IOException {
        if (client.indices().exists(e -> e.index(index)).value()) {
            log.info("Index '{}' already exists", index);
            return;
        }
        client.indices().create(c -> c.index(index).withJson(new StringReader(mapping)));
        log.info("Created index '{}'", index);
    }

    String questionMapping() {
        return """
                {
                  "mappings": {
                    "properties": {
                      "id":            { "type": "keyword" },
                      "board":         { "type": "keyword" },
                      "text":          { "type": "semantic_text", "inference_id": "%s" },
                      "variants":      { "type": "text", "index": false },
                      "variantTokens": { "type": "object", "enabled": false },
                      "asks":          { "type": "integer" },
                      "votes":         { "type": "integer" },
                      "popularity":    { "type": "integer" },
                      "createdAt":     { "type": "date", "format": "epoch_millis" },
                      "updatedAt":     { "type": "date", "format": "epoch_millis" }
                    }
                  }
                }
                """.formatted(properties.inferenceId());
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
