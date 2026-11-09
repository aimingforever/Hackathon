package com.cnh.goodfish.question;

import java.util.List;

/** API representation of a question group (without the stored token vectors). */
public record QuestionView(
        String id,
        String board,
        String text,
        List<String> variants,
        int asks,
        int votes,
        int popularity,
        long createdAt,
        long updatedAt) {

    static QuestionView of(QuestionGroup group) {
        return new QuestionView(group.id(), group.board(), group.text(), group.variants(),
                group.asks(), group.votes(), group.popularity(), group.createdAt(), group.updatedAt());
    }
}
