package com.cnh.goodfish.question;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Turns text into a sparse token vector using the Elasticsearch inference endpoint (ELSER). */
@Component
class Embedder {

    private final ElasticsearchClient client;
    private final QuestionProperties properties;

    Embedder(ElasticsearchClient client, QuestionProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    Map<String, Float> embed(String text) {
        try {
            return client.inference()
                    .sparseEmbedding(r -> r.inferenceId(properties.inferenceId()).input(text))
                    .sparseEmbedding()
                    .getFirst()
                    .embedding();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
