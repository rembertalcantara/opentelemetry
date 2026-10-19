package io.github.rembertalcantara.otel.order.api;

import io.github.rembertalcantara.otel.order.domain.OrderExceptions.InventoryUnavailableException;
import io.github.rembertalcantara.otel.order.domain.OrderExceptions.OrderNotFoundException;
import io.github.rembertalcantara.otel.order.domain.OrderExceptions.OutOfStockException;
import io.github.rembertalcantara.otel.order.domain.OrderExceptions.PaymentDeclinedException;
import io.github.rembertalcantara.otel.order.domain.OrderExceptions.UnknownProductException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 problem responses that carry the trace id, so a failed call is easy to look up. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private final Tracer tracer;

    public ApiExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    @ExceptionHandler({OrderNotFoundException.class, UnknownProductException.class})
    ProblemDetail notFound(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(OutOfStockException.class)
    ProblemDetail outOfStock(OutOfStockException e) {
        return problem(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(PaymentDeclinedException.class)
    ProblemDetail paymentDeclined(PaymentDeclinedException e) {
        return problem(HttpStatus.PAYMENT_REQUIRED, e);
    }

    @ExceptionHandler(InventoryUnavailableException.class)
    ProblemDetail inventoryUnavailable(InventoryUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, e);
    }

    private ProblemDetail problem(HttpStatus status, RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        Span span = tracer.currentSpan();
        if (span != null) {
            problem.setProperty("traceId", span.context().traceId());
        }
        return problem;
    }
}
