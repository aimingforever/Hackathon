package com.cnh.goodfish.question;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Runs against a real local Elasticsearch with ELSER. Skipped unless ES_LOCAL_PASSWORD is set
 * (for example after {@code source ~/elastic-start-local/.env}).
 */
@SpringBootTest(properties = {
        "goodfish.questions.index=questions-it",
        "goodfish.questions.board-index=boards-it",
        "goodfish.questions.create-index=true",
        "spring.elasticsearch.password=${ES_LOCAL_PASSWORD}"
})
@EnabledIfEnvironmentVariable(named = "ES_LOCAL_PASSWORD", matches = ".+")
class QuestionServiceElasticsearchIT {

    @Autowired
    QuestionService service;

    @Autowired
    BoardService boards;

    @Autowired
    ElasticsearchClient client;

    @AfterEach
    void deleteIndex() throws IOException {
        client.indices().delete(d -> d.index("questions-it", "boards-it").ignoreUnavailable(true));
    }

    @Test
    void mergesSimilarQuestionsAndKeepsDifferentOnesApart() {
        boards.setTopic("keynote", "eBPF in production: observability, networking and security in the Linux kernel");

        var slides = service.submit("keynote", "Will the slides be shared after the talk?");
        var slidesAgain = service.submit("keynote", "Where can I get the presentation slides?");
        var kernel = service.submit("keynote", "Which kernel version do I need for eBPF?");
        var kernelAgain = service.submit("keynote", "What is the minimum Linux kernel version supported?");
        var windows = service.submit("keynote", "Does eBPF work on Windows?");
        var otherBoard = service.submit("workshop", "Will the slides be shared after the talk?");

        assertThat(slides.merged()).isFalse();
        assertThat(slidesAgain.merged()).isTrue();
        assertThat(slidesAgain.group().id()).isEqualTo(slides.group().id());
        assertThat(slidesAgain.group().asks()).isEqualTo(2);
        assertThat(kernelAgain.merged()).isTrue();
        assertThat(kernelAgain.group().id()).isEqualTo(kernel.group().id());
        assertThat(windows.merged()).as("same topic, different question").isFalse();
        assertThat(otherBoard.merged()).isFalse();

        service.upvote(windows.group().id());
        service.upvote(windows.group().id());

        var ranked = service.top("keynote", 10);
        assertThat(ranked).hasSize(3);
        assertThat(ranked.getFirst().id()).isEqualTo(windows.group().id());
        assertThat(ranked.getFirst().popularity()).isEqualTo(3);
    }
}
