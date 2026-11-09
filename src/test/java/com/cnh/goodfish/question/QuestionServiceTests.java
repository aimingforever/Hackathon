package com.cnh.goodfish.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QuestionServiceTests {

    private static final Map<String, Float> EBPF_OVERHEAD = Map.of("ebpf", 2.5f, "overhead", 2.4f);
    private static final Map<String, Float> EBPF_WINDOWS = Map.of("ebpf", 2.5f, "windows", 2.4f);
    private static final Map<String, Float> SLIDES = Map.of("slides", 2.0f, "shared", 1.0f);

    @Test
    void mergesOnlyWhenSimilarityReachesThreshold() {
        assertThat(QuestionService.shouldMerge(0.56, 0.3)).isTrue();
        assertThat(QuestionService.shouldMerge(0.3, 0.3)).isTrue();
        assertThat(QuestionService.shouldMerge(0.17, 0.3)).isFalse();
        assertThat(QuestionService.shouldMerge(null, 0.3)).isFalse();
    }

    @Test
    void topicWordsNoLongerMakeDifferentQuestionsLookAlike() {
        Map<String, Double> noTopic = Map.of();
        Map<String, Double> ebpfTopic = SparseVectors.topicWeights(Map.of("ebpf", 2.0f), 0.5, 0.1);

        double withoutTopic = SparseVectors.cosine(EBPF_OVERHEAD, EBPF_WINDOWS, noTopic);
        double withTopic = SparseVectors.cosine(EBPF_OVERHEAD, EBPF_WINDOWS, ebpfTopic);

        assertThat(withoutTopic).isGreaterThan(0.5);
        assertThat(withTopic).isLessThan(0.05);
    }

    @Test
    void cosineOfIdenticalVectorsIsOne() {
        assertThat(SparseVectors.cosine(SLIDES, SLIDES, Map.of())).isCloseTo(1.0, within(1e-6));
        assertThat(SparseVectors.cosine(SLIDES, Map.of(), Map.of())).isZero();
    }

    @Test
    void bestMatchUsesClosestVariantOfEachGroup() {
        QuestionGroup overhead = new QuestionGroup("g1", "talk", "Does eBPF add much overhead?",
                List.of("a", "b"), List.of(EBPF_WINDOWS, EBPF_OVERHEAD), 2, 0, 2, 1, 1, List.of("a", "b"));
        QuestionGroup slides = new QuestionGroup("g2", "talk", "Will the slides be shared?",
                List.of("c"), List.of(SLIDES), 1, 0, 1, 1, 1, List.of("c"));

        QuestionService.Match best = QuestionService.bestMatch(List.of(slides, overhead), EBPF_OVERHEAD, Map.of());

        assertThat(best.id()).isEqualTo("g1");
        assertThat(best.similarity()).isCloseTo(1.0, within(1e-6));
        assertThat(QuestionService.bestMatch(List.of(), EBPF_OVERHEAD, Map.of())).isNull();
    }

    @Test
    void submissionsOnTheSameBoardAreSerializedButDifferentBoardsAreNot() throws Exception {
        QuestionService service = new QuestionService(null, null, null, null, Clock.systemUTC());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            // Same board: never more than one action inside the lock at a time.
            AtomicInteger inside = new AtomicInteger();
            AtomicInteger maxInside = new AtomicInteger();
            List<Future<Integer>> sameBoard = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                sameBoard.add(pool.submit(() -> service.withBoardLock("talk", () -> {
                    maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    sleep(50);
                    return inside.decrementAndGet();
                })));
            }
            for (Future<Integer> f : sameBoard) {
                f.get(5, TimeUnit.SECONDS);
            }
            assertThat(maxInside.get()).isEqualTo(1);

            // Different boards: a held lock on one board must not block another board.
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> holder = pool.submit(() -> service.withBoardLock("a", () -> {
                holding.countDown();
                await(release);
                return null;
            }));
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            Future<String> other = pool.submit(() -> service.withBoardLock("b", () -> "done"));
            assertThat(other.get(5, TimeUnit.SECONDS)).isEqualTo("done");
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void putMappingBodyIsTheInnerMappingsObject() {
        String body = QuestionIndexInitializer.propertiesOnly(QuestionIndexInitializer.BOARD_MAPPING);

        assertThat(body).startsWith("{").endsWith("}").contains("\"properties\"").doesNotContain("mappings");
    }

    @Test
    void keywordsBackfillTargetsOnlyDocumentsMissingKeywordsAndSeedsFromVariants() {
        String request = QuestionIndexInitializer.BACKFILL_KEYWORDS_REQUEST;

        assertThat(request).contains("must_not").contains("\"exists\"").contains("\"field\": \"keywords\"");
        assertThat(request).contains("ctx._source.keywords = new ArrayList(ctx._source.variants)");
    }

    @Test
    void searchTermsAreSafelyJsonEncoded() {
        assertThat(BoardInsightsService.json("say \"hi\"\n\\ ok")).isEqualTo("\"say \\\"hi\\\"\\n\\\\ ok\"");
        assertThat(BoardInsightsService.json(List.of("a", "b"))).isEqualTo("[\"a\",\"b\"]");
    }

    @Test
    void propertiesFallBackToDefaults() {
        QuestionProperties properties = new QuestionProperties(null, null, "", 0, 0, 0, true);

        assertThat(properties.index()).isEqualTo("questions");
        assertThat(properties.boardIndex()).isEqualTo("boards");
        assertThat(properties.inferenceId()).isEqualTo(".elser-2-elasticsearch");
        assertThat(properties.candidates()).isEqualTo(5);
        assertThat(properties.mergeThreshold()).isEqualTo(0.3);
        assertThat(properties.topicFloor()).isEqualTo(0.1);
    }
}
