package io.github.rembertalcantara.otel.common;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for metrics recorded through the native OpenTelemetry Metrics API.
 * (Micrometer metrics use Spring Boot's {@code management.otlp.metrics.export.*} instead.)
 *
 * @param enabled  whether to create the OpenTelemetry {@code SdkMeterProvider}
 * @param endpoint OTLP/HTTP metrics endpoint
 * @param interval how often metrics are exported
 */
@ConfigurationProperties("app.otel.metrics")
public record OtelMetricsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("http://localhost:4318/v1/metrics") String endpoint,
        @DefaultValue("10s") Duration interval) {
}
