package io.github.rembertalcantara.otel.common;

import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.core.task.TaskDecorator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Shared OpenTelemetry wiring used by every service in this repository.
 *
 * <ul>
 *   <li>Logs: installs the OpenTelemetry Logback appender so log records go out over OTLP,
 *       correlated with the active trace and span.</li>
 *   <li>Context propagation: copies the trace context (and baggage, MDC) onto threads
 *       used by {@code @Async} and task executors.</li>
 *   <li>Response headers: returns the trace id ({@code X-Trace-Id}) and a W3C
 *       {@code traceresponse} header so clients can find their trace.</li>
 *   <li>Noise control: actuator requests are not traced or measured.</li>
 *   <li>Native OpenTelemetry Metrics API: an {@code SdkMeterProvider} exporting over OTLP
 *       (see {@link OpenTelemetryMetricsConfiguration}).</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(OtelMetricsProperties.class)
@Import(OpenTelemetryMetricsConfiguration.class)
public class ObservabilityAutoConfiguration {

    /** Bridges Logback into the OpenTelemetry Logs SDK that Spring Boot configures. */
    @Bean
    OpenTelemetryLogAppenderInstaller openTelemetryLogAppenderInstaller(OpenTelemetry openTelemetry) {
        return new OpenTelemetryLogAppenderInstaller(openTelemetry);
    }

    /** Propagates ThreadLocal context (trace, baggage, MDC) to @Async / TaskExecutor threads. */
    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    ContextPropagatingTaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }

    /** Do not create spans or metrics for health checks and other actuator traffic. */
    @Bean
    ObservationPredicate ignoreActuatorRequests() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext serverContext) {
                return !serverContext.getCarrier().getRequestURI().startsWith("/actuator");
            }
            return true;
        };
    }

    /**
     * Runs inside Spring's HTTP server observation filter, so the server span is already current.
     */
    @Bean
    FilterRegistrationBean<TraceIdResponseFilter> traceIdResponseFilter(Tracer tracer) {
        var registration = new FilterRegistrationBean<>(new TraceIdResponseFilter(tracer));
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        return registration;
    }
}
