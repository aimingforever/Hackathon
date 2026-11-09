package com.cnh.goodfish.question;

import java.util.List;
import java.util.Map;

/**
 * A group of questions that mean the same thing, as stored in Elasticsearch. The first question
 * submitted becomes the group's {@code text}; later similar questions are kept in {@code variants}
 * and counted in {@code asks}.
 *
 * @param id            document id
 * @param board         board the group belongs to, for example a talk id or "topics"
 * @param text          canonical question text, indexed as semantic_text
 * @param variants      original wordings that were merged into this group (capped)
 * @param variantTokens ELSER expansion of each variant, used to compare new questions (not indexed)
 * @param asks          how many people submitted this question
 * @param votes         explicit upvotes
 * @param popularity    asks + votes, used for ranking
 * @param createdAt     epoch millis
 * @param updatedAt     epoch millis
 * @param keywords      every wording as plain text, for the keyword half of hybrid search
 */
public record QuestionGroup(
        String id,
        String board,
        String text,
        List<String> variants,
        List<Map<String, Float>> variantTokens,
        int asks,
        int votes,
        int popularity,
        long createdAt,
        long updatedAt,
        List<String> keywords) {

    /** Fields we read back from Elasticsearch (excludes inference data stored for semantic_text). */
    static final List<String> SOURCE_FIELDS = List.of(
            "id", "board", "text", "variants", "variantTokens",
            "asks", "votes", "popularity", "createdAt", "updatedAt");

    static QuestionGroup create(String id, String board, String text, Map<String, Float> tokens, long now) {
        return new QuestionGroup(id, board, text, List.of(text), List.of(tokens), 1, 0, 1, now, now, List.of(text));
    }
}
