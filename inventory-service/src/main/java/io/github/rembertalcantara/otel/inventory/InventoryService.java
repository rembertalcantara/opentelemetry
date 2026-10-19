package io.github.rembertalcantara.otel.inventory;

import io.micrometer.core.annotation.Counted;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Downstream side of the trace. Shows the <b>Micrometer Tracing API</b> (manual span, tags,
 * events, error), reading <b>propagated baggage</b>, gauges, a long-task timer and
 * {@code @Counted}.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    private static final int RESTOCK_THRESHOLD = 5;
    private static final int RESTOCK_AMOUNT = 20;

    private final Map<String, AtomicInteger> stock = new TreeMap<>(Map.of(
            "SKU-BOOK", new AtomicInteger(100),
            "SKU-MUG", new AtomicInteger(50),
            "SKU-PHONE", new AtomicInteger(10),
            "SKU-LAPTOP", new AtomicInteger(5)));

    private final Tracer tracer;
    private final LongTaskTimer restockTimer;

    public InventoryService(Tracer tracer, MeterRegistry registry) {
        this.tracer = tracer;
        stock.forEach((sku, level) -> Gauge.builder("inventory.stock.level", level, AtomicInteger::get)
                .description("Units in stock")
                .tag("sku", sku)
                .register(registry));
        this.restockTimer = LongTaskTimer.builder("inventory.restock.active")
                .description("Restock runs currently in progress")
                .register(registry);
    }

    public Map<String, Integer> levels() {
        Map<String, Integer> levels = new TreeMap<>();
        stock.forEach((sku, level) -> levels.put(sku, level.get()));
        return levels;
    }

    public int level(String sku) {
        return counter(sku).get();
    }

    public Reservation reserve(String sku, int quantity) {
        Span span = tracer.nextSpan().name("inventory.reserve")
                .tag("inventory.sku", sku)
                .tag("inventory.requested", quantity)
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            // Baggage set by order-service arrived in the W3C "baggage" header.
            Baggage tierBaggage = tracer.getBaggage("customer.tier");
            String tier = tierBaggage != null && tierBaggage.get() != null ? tierBaggage.get() : "unknown";
            span.tag("customer.tier", tier);

            AtomicInteger level = counter(sku);
            simulateWarehouseLookup(tier);
            span.event("stock.checked");

            int available;
            do {
                available = level.get();
                if (available < quantity) {
                    span.tag("inventory.outcome", "rejected");
                    throw new InsufficientStockException(sku, quantity, available);
                }
            } while (!level.compareAndSet(available, available - quantity));

            int remaining = available - quantity;
            span.tag("inventory.outcome", "reserved");
            span.event("stock.reserved");
            log.atInfo()
                    .addKeyValue("sku", sku)
                    .addKeyValue("inventory.remaining", remaining)
                    .log("Reserved {} x {} for a {} customer", quantity, sku, tier);
            return Reservation.of(sku, quantity, remaining);
        } catch (RuntimeException e) {
            span.error(e);   // records the exception and sets the span status to ERROR
            log.warn("Reservation failed: {}", e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    /** Spring observes this @Scheduled method (own trace); @Counted counts runs; the LongTaskTimer tracks duration while running. */
    @Counted("inventory.restock.runs")
    @Scheduled(fixedDelayString = "${app.restock.interval:PT20S}", initialDelayString = "PT20S")
    public void restock() {
        restockTimer.record(() -> stock.forEach((sku, level) -> {
            if (level.get() < RESTOCK_THRESHOLD) {
                int now = level.addAndGet(RESTOCK_AMOUNT);
                log.atInfo().addKeyValue("sku", sku).addKeyValue("inventory.level", now).log("Restocked {}", sku);
            }
        }));
    }

    private AtomicInteger counter(String sku) {
        AtomicInteger level = stock.get(sku);
        if (level == null) {
            throw new UnknownSkuException(sku);
        }
        return level;
    }

    private static void simulateWarehouseLookup(String tier) {
        long millis = "platinum".equals(tier) ? 5 : ThreadLocalRandom.current().nextLong(10, 60);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record Reservation(String reservationId, String sku, int quantity, int remaining) {
        static Reservation of(String sku, int quantity, int remaining) {
            return new Reservation("RES-" + UUID.randomUUID().toString().substring(0, 8), sku, quantity, remaining);
        }
    }

    public static class UnknownSkuException extends RuntimeException {
        public UnknownSkuException(String sku) {
            super("Unknown SKU " + sku);
        }
    }

    public static class InsufficientStockException extends RuntimeException {
        public InsufficientStockException(String sku, int requested, int available) {
            super("Requested " + requested + " x " + sku + " but only " + available + " in stock");
        }
    }
}
