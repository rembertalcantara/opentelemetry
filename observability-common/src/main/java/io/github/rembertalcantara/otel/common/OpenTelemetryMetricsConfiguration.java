package io.github.rembertalcantara.otel.common;

import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot exports metrics through Micrometer and does not create an OpenTelemetry
 * {@code SdkMeterProvider}. This configuration adds one so code can also use the native
 * OpenTelemetry Metrics API (counters, histograms, up-down counters, observable gauges),
 * sharing the same {@link Resource} (service.name, service.version, ...) as traces and logs.
 */
@Configuration(proxyBeanMethods = false)
class OpenTelemetryMetricsConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.otel.metrics.enabled", havingValue = "true", matchIfMissing = true)
    SdkMeterProvider sdkMeterProvider(Resource resource, OtelMetricsProperties properties) {
        OtlpHttpMetricExporter exporter = OtlpHttpMetricExporter.builder()
                .setEndpoint(properties.endpoint())
                .setCompression("gzip")
                .build();
        PeriodicMetricReader reader = PeriodicMetricReader.builder(exporter)
                .setInterval(properties.interval())
                .build();
        return SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(reader)
                // A View: every OTel-API histogram whose name ends in ".size" uses base-2
                // exponential buckets and keeps only the listed attributes.
                .registerView(
                        InstrumentSelector.builder().setType(InstrumentType.HISTOGRAM).setName("*.size").build(),
                        View.builder()
                                .setAggregation(Aggregation.base2ExponentialBucketHistogram())
                                .setAttributeFilter(Set.of("sku", "customer.tier"))
                                .build())
                .build();
    }

    @Bean
    OtelMeterFactory otelMeterFactory(ObjectProvider<SdkMeterProvider> meterProvider) {
        SdkMeterProvider sdk = meterProvider.getIfAvailable();
        MeterProvider provider = (sdk != null) ? sdk : MeterProvider.noop();
        return provider::get;
    }
}
