package io.github.rembertalcantara.otel.order.service;

import io.github.rembertalcantara.otel.order.api.PlaceOrderRequest;
import io.github.rembertalcantara.otel.order.client.InventoryClient;
import io.github.rembertalcantara.otel.order.domain.CustomerTier;
import io.github.rembertalcantara.otel.order.domain.Order;
import io.github.rembertalcantara.otel.order.domain.OrderExceptions.OrderNotFoundException;
import io.github.rembertalcantara.otel.order.domain.OrderRepository;
import io.github.rembertalcantara.otel.order.telemetry.OrderMetrics;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.Span;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates an order and shows the <b>Micrometer Observation API</b>: one
 * {@code Observation} produces both a span ("place-order") and a timer metric ("order.place").
 * Low-cardinality key values become metric tags and span attributes; high-cardinality ones
 * become span attributes only.
 *
 * <p>It also sets W3C <b>baggage</b> ({@code customer.tier}), which travels to every
 * downstream call and is copied into the logging MDC.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;
    private final PricingService pricing;
    private final InventoryClient inventory;
    private final PaymentGateway payments;
    private final NotificationService notifications;
    private final OrderRepository repository;
    private final OrderMetrics metrics;

    public OrderService(ObservationRegistry observationRegistry, Tracer tracer, PricingService pricing,
            InventoryClient inventory, PaymentGateway payments, NotificationService notifications,
            OrderRepository repository, OrderMetrics metrics) {
        this.observationRegistry = observationRegistry;
        this.tracer = tracer;
        this.pricing = pricing;
        this.inventory = inventory;
        this.payments = payments;
        this.notifications = notifications;
        this.repository = repository;
        this.metrics = metrics;
    }

    public Order placeOrder(PlaceOrderRequest request) {
        CustomerTier tier = CustomerTier.of(request.customerId());

        try (BaggageInScope baggage = tracer.createBaggageInScope("customer.tier", tier.tag())) {
            return Observation.createNotStarted("order.place", observationRegistry)
                    .contextualName("place-order")
                    .lowCardinalityKeyValue("customer.tier", tier.tag())
                    .highCardinalityKeyValue("customer.id", request.customerId())
                    .highCardinalityKeyValue("order.sku", request.sku())
                    .observe(() -> doPlaceOrder(request, tier));
        }
    }

    private Order doPlaceOrder(PlaceOrderRequest request, CustomerTier tier) {
        String orderId = UUID.randomUUID().toString();
        metrics.orderStarted();
        try {
            log.atInfo()
                    .addKeyValue("order.id", orderId)
                    .addKeyValue("order.sku", request.sku())
                    .addKeyValue("order.quantity", request.quantity())
                    .log("Placing order");

            BigDecimal total = pricing.quote(request.sku(), request.quantity(), tier);
            InventoryClient.Reservation reservation = inventory.reserve(request.sku(), request.quantity());
            String authorization = payments.authorize(orderId, request.customerId(), total);

            Span current = Span.current();
            Order order = new Order(orderId, request.sku(), request.quantity(), request.customerId(), tier, total,
                    reservation.reservationId(), authorization, current.getSpanContext().getTraceId(), Instant.now());
            repository.save(order, current.getSpanContext());
            metrics.orderPlaced(request.sku(), request.quantity(), tier, total);

            notifications.sendConfirmation(order);
            log.atInfo().addKeyValue("order.id", orderId).log("Order placed, total {} EUR", total);
            return order;
        } catch (RuntimeException e) {
            metrics.orderFailed(e.getClass().getSimpleName());
            throw e;
        } finally {
            metrics.orderFinished();
        }
    }

    public Order findOrder(String id) {
        return repository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    public List<Order> findAll() {
        return repository.findAll();
    }
}
