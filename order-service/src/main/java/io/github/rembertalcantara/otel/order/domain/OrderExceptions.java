package io.github.rembertalcantara.otel.order.domain;

/** Business failures. Each maps to an HTTP status in {@code ApiExceptionHandler}. */
public final class OrderExceptions {

    private OrderExceptions() {
    }

    public static final class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(String orderId) {
            super("Order " + orderId + " not found");
        }
    }

    public static final class UnknownProductException extends RuntimeException {
        public UnknownProductException(String sku) {
            super("Unknown product " + sku);
        }
    }

    public static final class OutOfStockException extends RuntimeException {
        public OutOfStockException(String sku, int quantity) {
            super("Not enough stock for " + quantity + " x " + sku);
        }
    }

    public static final class PaymentDeclinedException extends RuntimeException {
        public PaymentDeclinedException(String reason) {
            super("Payment declined: " + reason);
        }
    }

    public static final class InventoryUnavailableException extends RuntimeException {
        public InventoryUnavailableException(Throwable cause) {
            super("Inventory service unavailable", cause);
        }
    }
}
