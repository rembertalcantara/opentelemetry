package io.github.rembertalcantara.otel.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** Simulates an upstream caller by sending W3C traceparent and baggage headers by hand. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.otel.metrics.interval=1h",
        "management.otlp.metrics.export.enabled=false",
        "app.restock.interval=PT1H"
})
class InventoryContextPropagationTest {

    private static final String UPSTREAM_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String UPSTREAM_SPAN_ID = "00f067aa0ba902b7";

    @TestConfiguration(proxyBeanMethods = false)
    static class CaptureSpans {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    InMemorySpanExporter spans;

    @Autowired
    SdkTracerProvider tracerProvider;

    @Test
    void continuesTheUpstreamTraceAndReadsBaggage() {
        ResponseEntity<String> response = reserve("SKU-BOOK", 1);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo(UPSTREAM_TRACE_ID);

        List<SpanData> trace = finishedSpans();
        SpanData server = span(trace, "http post /inventory/{sku}/reservations");
        assertThat(server.getParentSpanId()).isEqualTo(UPSTREAM_SPAN_ID);

        SpanData reserve = span(trace, "inventory.reserve");
        assertThat(reserve.getParentSpanId()).isEqualTo(server.getSpanId());
        assertThat(reserve.getAttributes().get(AttributeKey.stringKey("customer.tier"))).isEqualTo("gold");
        assertThat(reserve.getAttributes().get(AttributeKey.stringKey("inventory.outcome"))).isEqualTo("reserved");
    }

    @Test
    void insufficientStockMarksTheSpanAsError() {
        ResponseEntity<String> response = reserve("SKU-LAPTOP", 1_000);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        SpanData reserve = span(finishedSpans(), "inventory.reserve");
        assertThat(reserve.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(reserve.getAttributes().get(AttributeKey.stringKey("inventory.outcome"))).isEqualTo("rejected");
    }

    private ResponseEntity<String> reserve(String sku, int quantity) {
        spans.reset();
        return RestClient.create("http://localhost:" + port).post()
                .uri("/inventory/{sku}/reservations?quantity={quantity}", sku, quantity)
                .header("traceparent", "00-" + UPSTREAM_TRACE_ID + "-" + UPSTREAM_SPAN_ID + "-01")
                .header("baggage", "customer.tier=gold")
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, r) -> {
                })
                .toEntity(String.class);
    }

    private List<SpanData> finishedSpans() {
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        return spans.getFinishedSpanItems().stream().filter(s -> s.getTraceId().equals(UPSTREAM_TRACE_ID)).toList();
    }

    private static SpanData span(List<SpanData> trace, String name) {
        return trace.stream().filter(s -> s.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("No span named " + name + " in " + trace));
    }
}
