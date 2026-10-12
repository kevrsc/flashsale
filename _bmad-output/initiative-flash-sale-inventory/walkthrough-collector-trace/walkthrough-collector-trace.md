# Collector shows one order's trace

Current block: review finished

## Blocks

- [x] **1. Intent** — done
- [x] **2. Broad strokes** — done
- [x] **3. The application sends the trace** — done
- [x] **4. The collector prints the trace** — done
- [x] **5. Periphery** — done

## 1. Intent

**Problem:** A successful order leaves no trace a person can read. Compose runs only PostgreSQL and Kafka, and the application does not export traces.

**Approach:** Add the local OpenTelemetry Collector and export this application's traces to it over OTLP HTTP. After one successful order, that order's trace id is in the collector log. Metrics, console log correlation, Grafana, and the Kafka trace header stay for later stories.

Taken verbatim from the Intent section of [story-the-collector-shows-one-order-s-trace-plan.md](../epic-local-sale-telemetry/story-the-collector-shows-one-order-s-trace-plan.md).

## 2. Broad strokes

The host process exports every trace to a collector that Compose runs on port 4318. The collector's only pipeline writes those traces to its own log.

Open these in order:

- [application.properties](../../../src/main/resources/application.properties:3) — sends traces to `localhost:4318` and keeps every trace
- [build.gradle](../../../build.gradle:24) — adds the OpenTelemetry starter that performs that export
- [compose.yaml](../../../compose.yaml:54) — runs the collector and publishes port 4318
- [otel-collector-config.yaml](../../../otel-collector-config.yaml:11) — accepts OTLP HTTP and prints traces at detailed verbosity

## 3. The application sends the trace

A successful order already creates spans inside the Boot process. This change turns on export. The starter registers the OTLP span exporter, and the two properties point it at the local collector and set the sample rate to every trace. The order path itself is unchanged.

- [build.gradle](../../../build.gradle:24) — `spring-boot-starter-opentelemetry`
- [application.properties](../../../src/main/resources/application.properties:3) — `management.opentelemetry.tracing.export.otlp.endpoint` and `management.tracing.sampling.probability=1`

## 4. The collector prints the trace

Compose starts `otel/opentelemetry-collector:0.162.0` and mounts the config at `/etc/otelcol/config.yaml`. The receiver listens on `0.0.0.0:4318`. The traces pipeline uses the debug exporter at detailed verbosity, which is the log a person reads for that order's trace id. There is no metrics pipeline and no logs pipeline in this commit.

- [compose.yaml](../../../compose.yaml:54) — service `otel-collector`, image `0.162.0`, host port `4318`
- [otel-collector-config.yaml](../../../otel-collector-config.yaml:1) — OTLP HTTP receiver
- [otel-collector-config.yaml](../../../otel-collector-config.yaml:11) — traces pipeline to the debug exporter

## 5. Periphery

- [story-the-collector-shows-one-order-s-trace-plan.md](../epic-local-sale-telemetry/story-the-collector-shows-one-order-s-trace-plan.md) — the story plan, including the manual collector-log check
