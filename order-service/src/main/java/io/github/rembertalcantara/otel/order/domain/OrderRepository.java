package io.github.rembertalcantara.otel.order.domain;

import io.opentelemetry.api.trace.SpanContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.stereotype.Repository;

/**
 * In-memory store. Also keeps the span context of each new order so the reconciliation job
 * can create a span that <em>links</em> to those orders' traces.
 */
@Repository
public class OrderRepository {

    private static final int MAX_PENDING_LINKS = 64;

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SpanContext> unreconciled = new ConcurrentLinkedQueue<>();

    public Order save(Order order, SpanContext createdIn) {
        orders.put(order.id(), order);
        if (createdIn.isValid()) {
            unreconciled.add(createdIn);
            while (unreconciled.size() > MAX_PENDING_LINKS) {
                unreconciled.poll();
            }
        }
        return order;
    }

    public Optional<Order> findById(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    public List<Order> findAll() {
        return orders.values().stream().sorted(Comparator.comparing(Order::createdAt).reversed()).toList();
    }

    public long count() {
        return orders.size();
    }

    /** Returns and clears the span contexts of orders not yet reconciled. */
    public List<SpanContext> drainUnreconciled() {
        List<SpanContext> drained = new ArrayList<>();
        SpanContext next;
        while ((next = unreconciled.poll()) != null) {
            drained.add(next);
        }
        return drained;
    }
}
