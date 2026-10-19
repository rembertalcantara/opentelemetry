package io.github.rembertalcantara.otel.order.service;

import io.github.rembertalcantara.otel.order.domain.Order;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Asynchronous context propagation. {@code @Async} runs on another (virtual) thread; the
 * {@code ContextPropagatingTaskDecorator} copies the trace context, baggage and MDC across,
 * so this span is still a child of the order's trace and its logs keep the same trace id.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ObservationRegistry observationRegistry;

    public NotificationService(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Async
    public void sendConfirmation(Order order) {
        Observation.createNotStarted("notification.send", observationRegistry)
                .contextualName("send-order-confirmation")
                .lowCardinalityKeyValue("notification.channel", "email")
                .highCardinalityKeyValue("order.id", order.id())
                .observe(() -> {
                    sleep(ThreadLocalRandom.current().nextLong(20, 80));
                    log.atInfo()
                            .addKeyValue("order.id", order.id())
                            .addKeyValue("thread.virtual", Thread.currentThread().isVirtual())
                            .log("Confirmation e-mail sent to customer {}", order.customerId());
                });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
