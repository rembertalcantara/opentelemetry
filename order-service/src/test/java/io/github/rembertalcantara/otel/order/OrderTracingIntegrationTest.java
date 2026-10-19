package io.github.rembertalcantara.otel.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * End-to-end check of the telemetry wiring: spans from all three APIs land in one trace,
 * and trace context plus baggage are propagated to the downstream service.
 * inventory-service is replaced by a tiny JDK HTTP server that records request headers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.otel.metrics.interval=1h",
        "management.otlp.metrics.export.enabled=false",
        "app.reconciliation.interval=PT1H"
})
class OrderTracingIntegrationTest {

    private static final HttpServer inventoryStub;
    private static final AtomicReference<Headers> lastInventoryRequestHeaders = new AtomicReference<>();

    static {
        try {
            inventoryStub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        inventoryStub.createContext("/inventory", exchange -> {
            lastInventoryRequestHeaders.set(exchange.getRequestHeaders());
            byte[] body = """
                    {"reservationId":"RES-TEST","sku":"SKU-BOOK","quantity":2,"remaining":98}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        inventoryStub.start();
    }

    @DynamicPropertySource
    static void inventoryUrl(DynamicPropertyRegistry registry) {
        registry.add("app.inventory.base-url", () -> "http://localhost:" + inventoryStub.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        inventoryStub.stop(0);
    }

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

    @BeforeEach
    void reset() {
        spans.reset();
        lastInventoryRequestHeaders.set(null);
    }

    @Test
    void placingAnOrderProducesOneTraceAcrossAllApisAndPropagatesContext() {
        ResponseEntity<String> response = post("""
                {"sku":"SKU-BOOK","quantity":2,"customerId":"vip-42"}
                """);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(response.getHeaders().getFirst("traceresponse")).startsWith("00-" + traceId + "-");

        // W3C trace context and baggage reached the downstream service
        Headers downstream = lastInventoryRequestHeaders.get();
        assertThat(downstream.getFirst("traceparent")).contains(traceId);
        assertThat(downstream.getFirst("baggage")).contains("customer.tier=platinum");

        List<SpanData> trace = finishedSpans(traceId);
        assertThat(trace).extracting(SpanData::getName)
                .contains("http post /orders",   // Spring MVC (automatic)
                        "place-order",           // Observation API
                        "quote-price",           // @Observed
                        "payment.authorize");    // OpenTelemetry API

        SpanData payment = span(trace, "payment.authorize");
        assertThat(payment.getAttributes().get(AttributeKey.stringKey("customer.tier"))).isEqualTo("platinum");
        assertThat(payment.getEvents()).extracting(e -> e.getName())
                .containsExactly("fraud-check.started", "fraud-check.completed");
        assertThat(payment.getParentSpanId()).isEqualTo(span(trace, "place-order").getSpanId());
    }

    @Test
    void declinedPaymentIsRecordedAsAnErrorSpan() {
        ResponseEntity<String> response = post("""
                {"sku":"SKU-MUG","quantity":1,"customerId":"fraudster-1"}
                """);

        assertThat(response.getStatusCode().value()).isEqualTo(402);
        assertThat(response.getBody()).contains("traceId");

        SpanData payment = span(finishedSpans(response.getHeaders().getFirst("X-Trace-Id")), "payment.authorize");
        assertThat(payment.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(payment.getEvents()).extracting(e -> e.getName()).contains("exception");
    }

    private ResponseEntity<String> post(String json) {
        return RestClient.create("http://localhost:" + port).post()
                .uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                })
                .toEntity(String.class);
    }

    private List<SpanData> finishedSpans(String traceId) {
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        return spans.getFinishedSpanItems().stream().filter(s -> s.getTraceId().equals(traceId)).toList();
    }

    private static SpanData span(List<SpanData> trace, String name) {
        return trace.stream().filter(s -> s.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("No span named " + name + " in " + trace));
    }
}
