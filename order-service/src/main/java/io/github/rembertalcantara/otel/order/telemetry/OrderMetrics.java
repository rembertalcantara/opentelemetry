package io.github.rembertalcantara.otel.order.telemetry;

import io.github.rembertalcantara.otel.common.OtelMeterFactory;
import io.github.rembertalcantara.otel.order.domain.CustomerTier;
import io.github.rembertalcantara.otel.order.domain.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Business metrics recorded two ways, side by side:
 *
 * <p><b>Micrometer</b> (Spring's recommended API, exported via OTLP by Spring Boot):
 * counters, a distribution summary with a percentile histogram (exemplars link its buckets to
 * traces), and a gauge.
 *
 * <p><b>Native OpenTelemetry Metrics API</b> (exported by the {@code SdkMeterProvider} from
 * observability-common): counter, histograms (with bucket advice and a View), up-down counter
 * and an asynchronous (observable) gauge.
 */
@Component
public class OrderMetrics {

    private static final AttributeKey<String> SKU = AttributeKey.stringKey("sku");
    private static final AttributeKey<String> TIER = AttributeKey.stringKey("customer.tier");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");

    private final MeterRegistry registry;
    private final DistributionSummary orderValue;

    private final LongCounter itemsSold;
    private final LongHistogram orderSize;
    private final DoubleHistogram paymentDuration;
    private final LongUpDownCounter ordersInFlight;

    public OrderMetrics(MeterRegistry registry, OtelMeterFactory meterFactory, OrderRepository repository) {
        this.registry = registry;

        // ---- Micrometer ----
        this.orderValue = DistributionSummary.builder("orders.value")
                .description("Value of placed orders")
                .baseUnit("EUR")
                .publishPercentileHistogram()
                .register(registry);
        Gauge.builder("orders.stored", repository, OrderRepository::count)
                .description("Orders currently held in memory")
                .register(registry);

        // ---- OpenTelemetry Metrics API ----
        Meter meter = meterFactory.meter("io.github.rembertalcantara.otel.order");
        this.itemsSold = meter.counterBuilder("shop.items.sold")
                .setDescription("Items sold")
                .setUnit("{item}")
                .build();
        this.orderSize = meter.histogramBuilder("shop.order.size")
                .setDescription("Items per order (exponential histogram via a View)")
                .setUnit("{item}")
                .ofLongs()
                .build();
        this.paymentDuration = meter.histogramBuilder("shop.payment.duration")
                .setDescription("Time spent authorizing payments")
                .setUnit("s")
                .setExplicitBucketBoundariesAdvice(List.of(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0))
                .build();
        this.ordersInFlight = meter.upDownCounterBuilder("shop.orders.in_flight")
                .setDescription("Orders currently being placed")
                .setUnit("{order}")
                .build();
        meter.gaugeBuilder("shop.orders.stored")
                .setDescription("Orders currently held in memory (observable gauge)")
                .setUnit("{order}")
                .ofLongs()
                .buildWithCallback(measurement -> measurement.record(repository.count()));
    }

    public void orderStarted() {
        ordersInFlight.add(1);
    }

    public void orderFinished() {
        ordersInFlight.add(-1);
    }

    public void orderPlaced(String sku, int quantity, CustomerTier tier, BigDecimal total) {
        Counter.builder("orders.placed")
                .description("Orders placed successfully")
                .tag("customer.tier", tier.tag())
                .register(registry)
                .increment();
        orderValue.record(total.doubleValue());

        Attributes attributes = Attributes.of(SKU, sku, TIER, tier.tag());
        itemsSold.add(quantity, attributes);
        orderSize.record(quantity, attributes);
    }

    public void orderFailed(String reason) {
        Counter.builder("orders.failed")
                .description("Orders that could not be placed")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void paymentAuthorized(Duration duration, boolean approved) {
        paymentDuration.record(duration.toNanos() / 1e9, Attributes.of(OUTCOME, approved ? "approved" : "declined"));
    }
}
