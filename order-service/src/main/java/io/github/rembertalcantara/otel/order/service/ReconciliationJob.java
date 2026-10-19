package io.github.rembertalcantara.otel.order.service;

import io.github.rembertalcantara.otel.order.domain.OrderRepository;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background work and <b>span links</b>. Spring observes {@code @Scheduled} methods
 * automatically (a new trace per run). Inside it, a batch span links to the traces of every
 * order it processes, which is how OpenTelemetry relates one operation to many causes.
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    private static final AttributeKey<Long> BATCH_SIZE = AttributeKey.longKey("reconciliation.batch_size");

    private final OrderRepository repository;
    private final Tracer tracer;

    public ReconciliationJob(OrderRepository repository, OpenTelemetry openTelemetry) {
        this.repository = repository;
        this.tracer = openTelemetry.getTracer("io.github.rembertalcantara.otel.order.reconciliation");
    }

    @Scheduled(fixedDelayString = "${app.reconciliation.interval:PT30S}", initialDelayString = "PT15S")
    public void reconcile() {
        List<SpanContext> batch = repository.drainUnreconciled();
        if (batch.isEmpty()) {
            return;
        }

        SpanBuilder builder = tracer.spanBuilder("orders.reconcile")
                .setAttribute(BATCH_SIZE, (long) batch.size());
        batch.forEach(order -> builder.addLink(order, Attributes.of(AttributeKey.stringKey("link.reason"), "order")));

        Span span = builder.startSpan();
        try (Scope ignored = span.makeCurrent()) {
            log.info("Reconciled {} orders", batch.size());
        } finally {
            span.end();
        }
    }
}
