package io.github.rembertalcantara.otel.order.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * @param customerId prefix {@code vip-} = platinum, {@code gold-} = gold,
 *                   {@code fraud} = payment declined; anything else = standard
 */
public record PlaceOrderRequest(
        @NotBlank String sku,
        @Positive @Max(100) int quantity,
        @NotBlank String customerId) {
}
