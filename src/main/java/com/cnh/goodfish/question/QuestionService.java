package com.cnh.goodfish.question;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /** Lock waits longer than this are logged as warnings. */
    static final long SLOW_LOCK_WAIT_MS = 1000;

    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);

    private static final String MERGE_SCRIPT = """
            ctx._source.asks += 1;
            ctx._source.popularity += 1;
            ctx._source.updatedAt = params.now;
            if (ctx._source.variants.size() < params.maxVariants && !ctx._source.variants.contains(params.text)) {
              ctx._source.variants.add(params.text);
              ctx._source.variantTokens.add(params.tokens);
              if (ctx._source.keywords == null) { ctx._source.keywords = new ArrayList(); }
              ctx._source.keywords.add(params.text);
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
    private final Map<String, ReentrantLock> boardLocks = new ConcurrentHashMap<>();
    private final QuestionTelemetry telemetry = new QuestionTelemetry(boardLocks);

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
        Span span = telemetry.tracer.spanBuilder("askonce.submit").setAttribute(QuestionTelemetry.BOARD, board).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            String question = text.strip();
            // Embedding does not depend on other submissions, so it runs before taking the board lock.
            Map<String, Float> tokens = telemetry.inSpan("askonce.embed", () -> embedder.embed(question));
            SubmitResult result = withBoardLock(board, () -> doSubmit(board, question, tokens));
            String outcome = result.merged() ? "merged" : "new";
            span.setAttribute(QuestionTelemetry.MERGED, result.merged());
            telemetry.submitted.add(1, Attributes.of(QuestionTelemetry.BOARD, board, QuestionTelemetry.RESULT, outcome));
            if (result.similarity() != null) {
                span.setAttribute(QuestionTelemetry.SIMILARITY, result.similarity());
                telemetry.similarity.record(result.similarity(), Attributes.of(QuestionTelemetry.BOARD, board));
            }
            return result;
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }

    <T> T withBoardLock(String board, Supplier<T> action) {
        ReentrantLock lock = boardLocks.computeIfAbsent(board, key -> new ReentrantLock());
        Span waitSpan = telemetry.tracer.spanBuilder("askonce.board_lock.wait")
                .setAttribute(QuestionTelemetry.BOARD, board)
                .setAttribute(QuestionTelemetry.LOCK_QUEUE, (long) lock.getQueueLength())
                .startSpan();
        long start = System.nanoTime();
        lock.lock();
        long waitedMs = (System.nanoTime() - start) / 1_000_000;
        waitSpan.setAttribute(QuestionTelemetry.LOCK_WAIT_MS, waitedMs);
        waitSpan.end();
        telemetry.lockWait.record(waitedMs, Attributes.of(QuestionTelemetry.BOARD, board));
        if (waitedMs > SLOW_LOCK_WAIT_MS) {
            log.warn("Slow submission on board '{}': waited {} ms for the board lock, {} more still queued",
                    board, waitedMs, lock.getQueueLength());
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private SubmitResult doSubmit(String board, String question, Map<String, Float> tokens) {
        List<QuestionGroup> candidates = telemetry.inSpan("askonce.find_candidates", () -> candidates(board, question));
        Span.current().setAttribute(QuestionTelemetry.CANDIDATES, (long) candidates.size());
        Match best = bestMatch(candidates, tokens, boards.topicWeights(board));

        if (best != null && shouldMerge(best.similarity(), properties.mergeThreshold())) {
            QuestionGroup merged = telemetry.inSpan("askonce.merge", () -> update(best.id(), MERGE_SCRIPT, Map.of(
                    "text", JsonData.of(question),
                    "tokens", JsonData.of(tokens),
                    "maxVariants", JsonData.of(MAX_VARIANTS)), Refresh.True));
            return new SubmitResult(QuestionView.of(merged), true, best.similarity());
        }

        QuestionGroup group = QuestionGroup.create(UUID.randomUUID().toString(), board, question, tokens, clock.millis());
        telemetry.inSpan("askonce.create_group", () -> {
            try {
                return client.index(i -> i.index(properties.index()).id(group.id()).document(group).refresh(Refresh.True));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
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
        return QuestionView.of(update(id, UPVOTE_SCRIPT, Map.of(), Refresh.WaitFor));
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

    /**
     * Applies a scripted update. Inside the board lock we use {@link Refresh#True}: the next submission
     * must see this change, and {@link Refresh#WaitFor} would hold the lock for up to a full refresh
     * interval (about 1 s), queueing every other submission on the board behind it.
     */
    private QuestionGroup update(String id, String script, Map<String, JsonData> extraParams, Refresh refresh) {
        Map<String, JsonData> params = new HashMap<>(extraParams);
        params.put("now", JsonData.of(clock.millis()));
        try {
            UpdateResponse<QuestionGroup> response = client.update(u -> u
                            .index(properties.index())
                            .id(id)
                            .retryOnConflict(5)
                            .refresh(refresh)
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
