package com.cnh.goodfish.question;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Custom traces and metrics for question handling. Uses the global OpenTelemetry instance, which the
 * OpenTelemetry Java agent provides in production; without the agent everything is a no-op.
 */
final class QuestionTelemetry {

    static final AttributeKey<String> BOARD = AttributeKey.stringKey("askonce.board");
    static final AttributeKey<String> RESULT = AttributeKey.stringKey("askonce.result");
    static final AttributeKey<Boolean> MERGED = AttributeKey.booleanKey("askonce.merged");
    static final AttributeKey<Double> SIMILARITY = AttributeKey.doubleKey("askonce.similarity");
    static final AttributeKey<Long> CANDIDATES = AttributeKey.longKey("askonce.candidates");
    static final AttributeKey<Long> LOCK_WAIT_MS = AttributeKey.longKey("askonce.board_lock.wait_ms");
    static final AttributeKey<Long> LOCK_QUEUE = AttributeKey.longKey("askonce.board_lock.queue_length");

    final Tracer tracer;
    final LongCounter submitted;
    final LongHistogram lockWait;
    final DoubleHistogram similarity;

    QuestionTelemetry(Map<String, ReentrantLock> boardLocks) {
        tracer = GlobalOpenTelemetry.getTracer("askonce");
        Meter meter = GlobalOpenTelemetry.getMeter("askonce");
        submitted = meter.counterBuilder("askonce.questions.submitted")
                .setDescription("Questions submitted, by board and result (merged or new)")
                .build();
        lockWait = meter.histogramBuilder("askonce.board_lock.wait")
                .setDescription("Time a submission waited for its board lock")
                .setUnit("ms")
                .ofLongs()
                .build();
        similarity = meter.histogramBuilder("askonce.match.similarity")
                .setDescription("Similarity of a submitted question to its best existing match")
                .build();
        // Only boards with waiters are reported, so made-up board names cannot grow metric cardinality.
        meter.gaugeBuilder("askonce.board_lock.queue_length")
                .setDescription("Submissions currently waiting for a board lock (boards with waiters only)")
                .ofLongs()
                .buildWithCallback(m -> boardLocks.forEach((board, lock) -> {
                    int queued = lock.getQueueLength();
                    if (queued > 0) {
                        m.record(queued, Attributes.of(BOARD, board));
                    }
                }));
    }

    /** Runs {@code action} in a child span of the current span. */
    <T> T inSpan(String name, Supplier<T> action) {
        Span span = tracer.spanBuilder(name).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return action.get();
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }
}
