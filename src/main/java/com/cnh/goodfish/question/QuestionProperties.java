package com.cnh.goodfish.question;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the question store.
 *
 * @param index          Elasticsearch index that holds question groups
 * @param boardIndex     Elasticsearch index that holds board topics
 * @param inferenceId    inference endpoint used for embeddings (ELSER by default; any sparse model works)
 * @param candidates     how many semantic search hits are checked before deciding to merge
 * @param mergeThreshold minimum topic-weighted cosine similarity for merging into an existing group
 * @param topicFloor     weight given to the board topic's tokens when comparing questions
 * @param createIndex    whether to create the indices on startup when they do not exist
 */
@ConfigurationProperties("goodfish.questions")
public record QuestionProperties(
        String index,
        String boardIndex,
        String inferenceId,
        int candidates,
        double mergeThreshold,
        double topicFloor,
        boolean createIndex) {

    public QuestionProperties {
        if (index == null || index.isBlank()) {
            index = "questions";
        }
        if (boardIndex == null || boardIndex.isBlank()) {
            boardIndex = "boards";
        }
        if (inferenceId == null || inferenceId.isBlank()) {
            inferenceId = ".elser-2-elasticsearch";
        }
        if (candidates <= 0) {
            candidates = 5;
        }
        if (mergeThreshold <= 0) {
            mergeThreshold = 0.3;
        }
        if (topicFloor <= 0) {
            topicFloor = 0.1;
        }
    }
}
