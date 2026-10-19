package io.github.rembertalcantara.otel.common;

import io.opentelemetry.api.metrics.Meter;

/**
 * Entry point for the native OpenTelemetry Metrics API: returns a {@link Meter} for an
 * instrumentation scope, or a no-op meter when {@code app.otel.metrics.enabled=false}.
 */
@FunctionalInterface
public interface OtelMeterFactory {

    Meter meter(String instrumentationScopeName);
}
