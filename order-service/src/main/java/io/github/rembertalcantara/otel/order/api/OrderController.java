package io.github.rembertalcantara.otel.order.api;

import io.github.rembertalcantara.otel.order.domain.Order;
import io.github.rembertalcantara.otel.order.service.OrderService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP server spans and the {@code http.server.requests} metric come from Spring MVC automatically. */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    public ResponseEntity<Order> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        Order order = orders.placeOrder(request);
        return ResponseEntity.created(URI.create("/orders/" + order.id())).body(order);
    }

    @GetMapping("/{id}")
    public Order getOrder(@PathVariable String id) {
        return orders.findOrder(id);
    }

    @GetMapping
    public List<Order> listOrders() {
        return orders.findAll();
    }
}
