---
id: 1
type: story
title: "BMad setup test — safe to delete"
parent: none
covers: []
after: []
refined: false
hitl: false
risk: low
---

# BMad setup test — safe to delete

## Description

This ticket proves the repo store can create a backlog story, read it back, mark it in progress, and drop it.

## Acceptance Criteria

1. **The store round-trip**
   **Given** an empty backlog
   **When** this ticket is created, queried, marked in progress, and dropped
   **Then** the file and its plan show the dropped status

## Boundaries

- Must not change: the flash-sale inventory spec, the architecture spine, or any real story.

## References

- store — _bmad/custom/ticketing-store-config.toml
