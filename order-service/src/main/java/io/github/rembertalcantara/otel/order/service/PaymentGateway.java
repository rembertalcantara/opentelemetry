package io.github.rembertalcantara.otel.order.service;

import io.github.rembertalcantara.otel.order.domain.OrderExceptions.PaymentDeclinedException;
import io.github.rembertalcantara.otel.order.telemetry.OrderMetrics;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Manual instrumentation with the <b>native OpenTelemetry Tracing API</b>: span kind,
 * typed attributes, span events, exception recording, span status, and reading W3C baggage
 * through {@link Baggage#current()}. The span nests under whatever span is current, because
 * Spring's Micrometer bridge and the OpenTelemetry API share the same {@code Context}.
 */
@Component
public class PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(PaymentGateway.class);

    static final AttributeKey<String> ORDER_ID = AttributeKey.stringKey("order.id");
    static final AttributeKey<Double> AMOUNT = AttributeKey.doubleKey("payment.amount");
    static final AttributeKey<Boolean> FRAUD_SUSPECTED = AttributeKey.booleanKey("payment.fraud_suspected");

    private final Tracer tracer;
    private final OrderMetrics metrics;

    public PaymentGateway(OpenTelemetry openTelemetry, OrderMetrics metrics) {
        this.tracer = openTelemetry.getTracer("io.github.rembertalcantara.otel.order.payment", "1.0.0");
        this.metrics = metrics;
    }

    public String authorize(String orderId, String customerId, BigDecimal amount) {
        long start = System.nanoTime();
        Span span = tracer.spanBuilder("payment.authorize")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(ORDER_ID, orderId)
                .setAttribute(AMOUNT, amount.doubleValue())
                .setAttribute("payment.currency", "EUR")
                .setAttribute("payment.provider", "demo-pay")
                .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            // Baggage set upstream (order-service) is readable through the OTel API too.
            String tier = Baggage.current().getEntryValue("customer.tier");
            if (tier != null) {
                span.setAttribute("customer.tier", tier);
            }

            span.addEvent("fraud-check.started");
            simulateProviderLatency();
            boolean fraudSuspected = customerId.startsWith("fraud");
            span.addEvent("fraud-check.completed", Attributes.of(FRAUD_SUSPECTED, fraudSuspected));

            if (fraudSuspected) {
                var declined = new PaymentDeclinedException("fraud suspected");
                span.recordException(declined);
                span.setStatus(StatusCode.ERROR, "payment declined");
                log.warn("Payment declined for order {}", orderId);
                metrics.paymentAuthorized(Duration.ofNanos(System.nanoTime() - start), false);
                throw declined;
            }

            String authorization = "AUTH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            span.setAttribute("payment.authorization", authorization);
            span.setStatus(StatusCode.OK);
            metrics.paymentAuthorized(Duration.ofNanos(System.nanoTime() - start), true);
            log.info("Payment authorized for order {}", orderId);
            return authorization;
        } finally {
            span.end();
        }
    }

    private static void simulateProviderLatency() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(15, 120));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
