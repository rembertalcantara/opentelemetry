# OpenTelemetry with Spring Boot 4 and Java 25

Two Spring Boot 4.1 services that use all three OpenTelemetry signals (traces, metrics and logs)
end to end. They send everything over OTLP to a local Grafana stack.

```
client ──► order-service :8080 ──(HTTP: traceparent + baggage)──► inventory-service :8081
               │  @Async notification, @Scheduled reconciliation        │  @Scheduled restock
               └──────────────── OTLP (traces, metrics, logs) ──────────┴──► grafana/otel-lgtm
                                                                             (Collector, Tempo, Loki,
                                                                              Prometheus, Grafana :3000)
```

## Requirements

- JDK 25
- Maven 3.9+
- Docker

## Run it

```bash
# 1. Start the observability stack (OTLP on :4317/:4318, Grafana on http://localhost:3000)
docker compose up -d lgtm

# 2. Start both services (two terminals)
mvn -pl inventory-service -am spring-boot:run
mvn -pl order-service -am spring-boot:run

# 3. Send traffic, including failures
scripts/generate-traffic.sh 50
```

To run everything in containers instead, use `docker compose up -d --build` (it starts Grafana and both services).

Grafana at http://localhost:3000 opens on the provisioned dashboard **OpenTelemetry Demo – All Metrics**. It has
panels for every metric the services emit: orders, the native OpenTelemetry `shop.*` metrics, pipeline timers,
inventory, HTTP, and JVM. The JSON lives in `grafana/dashboards/` and is mounted by `compose.yaml`. Edit it in
Grafana (changes are not persisted across `docker compose down`) or in the file.

To dig into individual signals, go to **Explore**:

- **Tempo** (traces): search `service.name = order-service`. Every `X-Trace-Id` printed by the script is a trace id you can paste in.
- **Loki** (logs): use `{service_name="order-service"}`. Each log line carries `trace_id`, `span_id`, `customer.tier` and the key-value attributes.
- **Prometheus** (metrics): try `orders_placed_total`, `shop_items_sold_total`, `http_server_requests_seconds_bucket` (with exemplars) or `inventory_stock_level`.

### Postman

Import `postman/otel-demo.postman_collection.json`. It has a request for every endpoint of both services (orders,
failure scenarios, inventory, actuator), with tests on the status codes. Base URLs and the default SKU, quantity and customer
are collection variables. Run the whole collection with the Collection Runner or `newman run postman/otel-demo.postman_collection.json`.

### Performance tests (k6)

`k6s/` has [k6](https://grafana.com/docs/k6/) scripts that exercise every endpoint of both services: orders, each failure
scenario (402/404/409/400), inventory, and the actuator. They run in the `grafana/k6` Docker image, so nothing needs installing.
Start the stack first (`docker compose up -d --build`), then:

```bash
docker compose run --rm k6                          # smoke: 1 user, every endpoint once (default)
docker compose run --rm k6 run /scripts/load.js     # 20 users for ~3 min
docker compose run --rm k6 run /scripts/stress.js   # steps up to 150 users, aborts when >5% fail
docker compose run --rm k6 run /scripts/spike.js    # sudden jump to 100 users
docker compose run --rm k6 run /scripts/soak.js     # 15 users for 30 min, to look for leaks
```

Thresholds (error rate, p95 latency per endpoint group) fail the run with a non-zero exit code. Expected error responses
(402/404/409/400) are not counted as failures, and a 409 on orders is accepted because the stock is finite. k6 sends its
metrics to the collector too, so the Grafana dashboard has a **k6 load test** row next to the service metrics.
To run against a local setup instead of the containers: `docker run --rm -i --network host -v "$PWD/k6s:/scripts:ro" grafana/k6 run /scripts/load.js`.

### Try a single request

```bash
curl -i -X POST localhost:8080/orders -H 'Content-Type: application/json' \
     -d '{"sku":"SKU-BOOK","quantity":2,"customerId":"vip-42"}'
```

The customer id controls the scenario:

| Customer id | Effect |
|---|---|
| `vip-…` | Platinum tier, 15% discount, fastest inventory path |
| `gold-…` | Gold tier, 5% discount |
| `fraud…` | Payment declined (402) and an error span |
| any other | Standard tier |

Other failures to try: the SKU `SKU-UNKNOWN` returns 404. Ordering many `SKU-LAPTOP` returns 409 (out of stock), and a restock job refills it every 20 s.

## What is covered

### Traces

| Feature | Where |
|---|---|
| Automatic HTTP server spans (Spring MVC) | every controller |
| Automatic HTTP client spans + W3C `traceparent` propagation | `InventoryClient` (Boot's `RestClient.Builder`) |
| Micrometer **Observation API** (span + timer in one), low/high-cardinality key values | `OrderService.placeOrder` |
| Declarative `@Observed` | `PricingService.quote` |
| Micrometer **Tracing API**: manual span, tags, events, `span.error()` | `InventoryService.reserve` |
| Native **OpenTelemetry API**: `SpanKind`, typed attributes, events, `recordException`, `setStatus` | `PaymentGateway` |
| **Span links** (one batch span linked to many order traces) | `ReconciliationJob` |
| Automatic `@Scheduled` spans | `ReconciliationJob`, `InventoryService.restock` |
| **Baggage**: set (Micrometer), read (Micrometer + OTel `Baggage.current()`), propagated, copied to MDC | `OrderService`, `PaymentGateway`, `InventoryService` |
| Async context propagation (`@Async` on virtual threads) | `NotificationService` + `ContextPropagatingTaskDecorator` |
| Sampler (`parent-based-trace-id-ratio`), sampling probability, span limits | `application.yml` |
| Trace id returned to callers (`X-Trace-Id`, W3C `traceresponse`, in problem details) | `TraceIdResponseFilter`, `ApiExceptionHandler` |
| Excluding actuator traffic (`ObservationPredicate`) | `ObservabilityAutoConfiguration` |

### Metrics

| Feature | Where |
|---|---|
| Built-in JVM, HTTP server/client, executor metrics exported over OTLP | Spring Boot (Micrometer → OTLP) |
| Micrometer `Counter`, `DistributionSummary` (percentile histogram), `Gauge`, `LongTaskTimer`, `@Counted` | `OrderMetrics`, `InventoryService` |
| **Exemplars** linking histogram buckets to traces | automatic in Boot 4.1 with OTLP + tracing (`percentiles-histogram` enabled) |
| Native **OpenTelemetry Metrics API**: `LongCounter`, `LongHistogram`, `DoubleHistogram` with bucket advice, `LongUpDownCounter`, observable gauge | `OrderMetrics` |
| `SdkMeterProvider` with OTLP exporter and a **View** (exponential histogram, attribute filter) | `OpenTelemetryMetricsConfiguration` |
| Common tags on every observation | `management.observations.key-values` |

### Logs

| Feature | Where |
|---|---|
| Logback → OpenTelemetry Logs SDK → OTLP | `logback-spring.xml` + `OpenTelemetryLogAppenderInstaller` |
| Trace/span correlation on each record | automatic |
| SLF4J 2 key-values, MDC (incl. baggage), code location and thread as attributes | appender options in `logback-spring.xml` |
| Correlation ids in console output | `logging.pattern.correlation` |

### Resource

`service.name` (from `spring.application.name`), `service.namespace`, `service.version`, and
`deployment.environment.name` are set via `management.opentelemetry.resource-attributes`. They are shared by traces, logs and OTel-API metrics. Standard `OTEL_*` environment variables (for example `OTEL_RESOURCE_ATTRIBUTES`) are also honoured.

## Configuration

Everything points at `http://localhost:4318` by default. Set `OTLP_ENDPOINT` to send elsewhere,
for example to another collector or a vendor endpoint:

| Variable | Default | Used for |
|---|---|---|
| `OTLP_ENDPOINT` | `http://localhost:4318` | traces, metrics and logs (OTLP/HTTP) |
| `INVENTORY_URL` | `http://localhost:8081` | order-service → inventory-service |
| `DEPLOYMENT_ENV` | `local` | resource attribute and common metric tag |

## Project layout

```
observability-common/   shared auto-configuration (log bridge, OTel metrics SDK, propagation, trace-id header)
order-service/          entry point: orders, pricing, payment, notifications, reconciliation
inventory-service/      stock, reservations, restock job
compose.yaml            grafana/otel-lgtm + optional containers for both services
scripts/                traffic generator
```

## Tests

```bash
mvn verify
```

The integration tests capture spans in memory and check four things:

- Spans from the Observation API, `@Observed` and the OpenTelemetry API end up in one trace with the right parent/child links.
- `traceparent` and `baggage` reach the downstream service.
- Errors are recorded on spans.
- inventory-service continues an upstream trace and reads its baggage.
