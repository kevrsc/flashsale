---
title: 'Kafka UI'
type: 'feature'
ticket: ''
created: '2026-10-13'
status: 'built'
route: 'oneshot'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 0
context: []
baseline_revision: b93ad7663800c92ea27433656e2016544a3da45f
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Compose runs the Kafka broker, and the only way to see a topic or a record is the console consumer. There is no page for the broker.

**Approach:** Add kafbat Kafka UI `v1.5.0` to this Compose file, on host port `8088`, already pointed at the existing broker listener `kafka:19092`. Record that image and port in AD-10, the version table, the topology diagram, and the README. Leave the broker listeners, the application bootstrap address, and finished story plans unchanged.

</frozen-after-approval>

## Implementation Notes

Oneshot: one Compose service plus the sentences that already list the local services. Well under 100 lines, and the broker wiring is already fixed.

The UI image is `kafbat/kafka-ui:v1.5.0`, the latest release tag, published 2026-04-20. Host port `8088` avoids the Boot process on `8080`. The cluster name is `flashsale`. The UI waits until `kafka-init` has created `inventory.order-accepted`. No login is configured, matching the local Grafana viewer.

## Plan Change Log

## Review Triage Log

- Structural seed file tree omitted `kafka-ui`. Verdict: low. Real, caused by this change. Patched the `compose.yaml` comment so it lists `kafka-ui`.
- AD-10 listed `kafka-ui` as a name other containers call, and left out `kafka`, which is the name the UI uses. Verdict: medium. Real. Patched the sentence so the service names include `kafka` and `kafka-ui`, and the UI reaches the broker as `kafka`.

## Verification

**Commands:**
- `docker compose up -d` -- expected: the Kafka UI container is running and `http://localhost:8088` answers
- Query the UI's cluster API -- expected: the configured cluster lists topic `inventory.order-accepted`

**Manual checks (if no CLI):**
- Open `http://localhost:8088` and confirm the cluster is already selected, with topic `inventory.order-accepted` visible, without typing a bootstrap address
