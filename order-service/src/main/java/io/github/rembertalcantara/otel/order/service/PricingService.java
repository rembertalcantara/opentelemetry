package io.github.rembertalcantara.otel.order.service;

import io.github.rembertalcantara.otel.order.domain.CustomerTier;
import io.github.rembertalcantara.otel.order.domain.OrderExceptions.UnknownProductException;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Declarative instrumentation: {@link Observed} turns this method into a child span
 * ("quote-price") <em>and</em> a timer metric ("pricing.quote") with no tracing code.
 * Requires the AspectJ starter and {@code management.observations.annotations.enabled=true}.
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    static final Map<String, BigDecimal> CATALOG = Map.of(
            "SKU-BOOK", new BigDecimal("12.50"),
            "SKU-MUG", new BigDecimal("8.90"),
            "SKU-PHONE", new BigDecimal("799.00"),
            "SKU-LAPTOP", new BigDecimal("1299.00"));

    @Observed(name = "pricing.quote", contextualName = "quote-price",
            lowCardinalityKeyValues = {"pricing.strategy", "tiered-discount"})
    public BigDecimal quote(String sku, int quantity, CustomerTier tier) {
        BigDecimal unitPrice = CATALOG.get(sku);
        if (unitPrice == null) {
            throw new UnknownProductException(sku);
        }
        BigDecimal gross = unitPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal total = gross.subtract(gross.multiply(tier.discount())).setScale(2, RoundingMode.HALF_UP);
        log.atDebug()
                .addKeyValue("sku", sku)
                .addKeyValue("pricing.total", total)
                .log("Quoted {} x {} for a {} customer", quantity, sku, tier.tag());
        return total;
    }
}
