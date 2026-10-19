package io.github.rembertalcantara.otel.order.domain;

import java.math.BigDecimal;
import java.util.Locale;

/** Derived from the customer id so the demo needs no customer database. */
public enum CustomerTier {

    STANDARD(BigDecimal.ZERO),
    GOLD(new BigDecimal("0.05")),
    PLATINUM(new BigDecimal("0.15"));

    private final BigDecimal discount;

    CustomerTier(BigDecimal discount) {
        this.discount = discount;
    }

    public BigDecimal discount() {
        return discount;
    }

    /** Lower-case value used as a metric tag, span attribute and W3C baggage entry. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CustomerTier of(String customerId) {
        return switch (customerId) {
            case String id when id.startsWith("vip-") -> PLATINUM;
            case String id when id.startsWith("gold-") -> GOLD;
            default -> STANDARD;
        };
    }
}
