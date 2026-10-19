package io.github.rembertalcantara.otel.common;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;

/**
 * Spring Boot configures the OpenTelemetry Logs SDK and its OTLP exporter, but it does not
 * attach an appender to Logback. The {@code OTEL} appender declared in {@code logback-spring.xml}
 * buffers events until this installer hands it the {@link OpenTelemetry} instance.
 */
public class OpenTelemetryLogAppenderInstaller implements InitializingBean {

    private final OpenTelemetry openTelemetry;

    public OpenTelemetryLogAppenderInstaller(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
