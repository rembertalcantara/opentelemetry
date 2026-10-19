package io.github.rembertalcantara.otel.order.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record Order(
        String id,
        String sku,
        int quantity,
        String customerId,
        CustomerTier tier,
        BigDecimal total,
        String reservationId,
        String paymentAuthorization,
        String traceId,
        Instant createdAt) {
}
