package io.github.rembertalcantara.otel.order.client;

import io.github.rembertalcantara.otel.order.domain.OrderExceptions.InventoryUnavailableException;
import io.github.rembertalcantara.otel.order.domain.OrderExceptions.OutOfStockException;
import io.github.rembertalcantara.otel.order.domain.OrderExceptions.UnknownProductException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Cross-service propagation. The {@link RestClient.Builder} is Spring Boot's instrumented one,
 * so every call gets a CLIENT span and carries {@code traceparent} plus {@code baggage}
 * headers to inventory-service. (A client built with {@code RestClient.create()} would not.)
 */
@Component
public class InventoryClient {

    private final RestClient restClient;

    public InventoryClient(RestClient.Builder builder, @Value("${app.inventory.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public Reservation reserve(String sku, int quantity) {
        try {
            return restClient.post()
                    // URI template keeps the "uri" metric tag low-cardinality.
                    .uri("/inventory/{sku}/reservations?quantity={quantity}", sku, quantity)
                    .retrieve()
                    .onStatus(status -> status.isSameCodeAs(HttpStatus.CONFLICT), (request, response) -> {
                        throw new OutOfStockException(sku, quantity);
                    })
                    .onStatus(status -> status.isSameCodeAs(HttpStatus.NOT_FOUND), (request, response) -> {
                        throw new UnknownProductException(sku);
                    })
                    .body(Reservation.class);
        } catch (ResourceAccessException e) {
            throw new InventoryUnavailableException(e);
        }
    }

    public record Reservation(String reservationId, String sku, int quantity, int remaining) {
    }
}
