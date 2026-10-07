---
title: 'Modern flash sale order page'
type: 'feature'
ticket: ''
created: '2026-10-07'
status: 'built'
baseline_revision: 'c2212bac86b631a7ae36fdc4eed20e6c0c388e2b'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 1
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `GET /` and `POST /` are the only order route, and the Thymeleaf page is an unstyled form. The shopper still submits a user id and a quantity and must read the sentence inventory returns.

**Approach:** Replace that page with a modern layout in the same Boot app. Keep posting `userId` and `quantity` to `/`. Show `OrderOutcome.message()` unchanged, and style it.

## Boundaries & Constraints

**Always:** One Thymeleaf form is the only HTTP order route (AD-7). The page calls `Admission` in process and opens no transaction. `GET /` does not call inventory. A turn-away is retried with jitter in `OrderPage` and is never rendered. The visible outcome is exactly one of: "The order is in process", "Wait one hour before ordering again", "The item has sold out", "The order is refused", "The order was not accepted". User id is an opaque string. No item id is collected or shown.

**Never:** A second order API, a JSON client, an item field, or `item-1` in the rendered body. Do not replace or prefix the outcome sentence. Do not put "sold out" or "in process" in static copy: success HTML rejects "sold out", and sold-out HTML rejects "in process". Do not change inventory, admission, the schema, or the retry loop. No page tally or metrics endpoint (AD-8).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Open | `GET /` | Form posts `userId` and `quantity` to `/`. No outcome sentence. Request total unchanged. | No error expected |
| Success | User set, quantity ≥ 1, pool covers at least one unit under the limit of 3 | Body contains "The order is in process" and not "sold out", "The order was not accepted", `name="itemId"`, or `item-1`. | No error expected |
| Wait | Same customer inside the hour, pool above zero | "Wait one hour before ordering again". Body does not contain "The order is in process". | No error expected |
| Sold out | Pool empty, including during a wait | "The item has sold out". Body does not contain "in process", the wait sentence, or "The order is refused". | No error expected |
| Refused | Customer holds 3, hour elapsed, pool above zero | "The order is refused". Not the in-process sentence. Not the wait sentence. | No error expected |
| Not an order | Blank user id, or quantity under 1 | "The order was not accepted". Not the in-process sentence. Nothing deducted. | No error expected |

</frozen-after-approval>

## Code Map

- `src/main/java/com/flashsale/page/OrderPage.java` — `form()` (lines 24–27) stays off inventory. `order()` (lines 29–33) adds `userId`, `quantity`, the message, and `tone` to the model. Reuse `shopperOutcome()` and `jitter()` (lines 35–52) unchanged.
- `src/main/java/com/flashsale/inventory/OrderOutcome.java` — message factories (lines 5–27). Do not edit.
- `src/main/resources/templates/order.html` — replace the bare form. Keep `post` to `/`, `name="userId"`, `name="quantity"`, and `th:text` of the outcome.
- `src/main/resources/static/order.css` — new. Served at `/order.css`. Tests read HTML, not this file.
- `src/test/java/com/flashsale/OrderPoolEventTest.java` — `order()` posts both params and reads the body. Extend `openingThePageLeavesTheRequestTotalUnchanged` so the open row checks the form and that none of the five sentences are in the body. Do not weaken any other assertion.
- `src/main/java/com/flashsale/page/package-info.java` — page may depend on inventory. Inventory must not depend on page.

## Tasks & Acceptance

**Execution:**
- [x] `src/main/java/com/flashsale/page/OrderPage.java` -- On POST, add `userId`, `quantity`, `outcome` (the message), and `tone` (`go`, `wait`, `stop`, `empty`, `bad`) from that message. Leave `GET /` and the retry loop. -- The template must not repeat the other sentences as text.
- [x] `src/main/resources/templates/order.html` -- Headline, three facts (10 at the open, 3 per person, 1 hour between orders), labeled fields, submit, one outcome region. `th:value` on the inputs. Outcome: `role="status"`, `th:text="${outcome}"`, `data-tone` from `tone`, only when `outcome` is non-null. Link `/order.css`. -- The shopper submits and reads the result on the new page.
- [x] `src/main/resources/static/order.css` -- Warm near-black stage, paper ticket, acid action color, one color per `data-tone`, one column below 860px. System fallbacks behind Instrument Serif and Bricolage Grotesque. Color the outcome sentence only. No `content` label, no `::before` or `::after` text, and no other words around that sentence. -- The shopper reads the inventory sentence alone.
- [x] `src/test/java/com/flashsale/OrderPoolEventTest.java` -- In `openingThePageLeavesTheRequestTotalUnchanged`, assert the form posts `userId` and `quantity` to `/` and that the body contains none of the five outcome sentences, no `name="itemId"`, and no `item-1`. Leave every other assertion as it is. -- The open row of the matrix is checked on `GET /`.

**Acceptance Criteria:**
- Given `GET /`, when HTML returns, then the form posts `userId` and `quantity` to `/`, no outcome sentence is shown, and the body has no `name="itemId"` and no `item-1`.
- Given a POST, when the outcome is shown, then the inputs still hold the submitted user id and quantity, and the visible outcome text is exactly `OrderOutcome.message()`.
- Given a narrow viewport, when the page is shown, then the ticket stacks under the headline and the form can still be submitted.

## Implementation Notes

## Plan Change Log

- Trigger: quick review, `order.css` `.outcome::before` prefixes the inventory sentence with Accepted, Hold, Limit, None left, or Check the form.
- Amended: Design Notes and the CSS and test tasks. The outcome region is color only. No generated label.
- Known-bad state avoided: a shopper reading a second status word above the inventory sentence.
- KEEP: night-drop layout (dark stage, paper ticket, acid button, headline "Take what's left.", three facts), form posts `userId` and `quantity` to `/`, inputs keep submitted values, `th:text` of the message, `data-tone` colors with no generated text, one column below 860px, `OrderPage` model attributes and tone switch, and the opening-page assertions.

## Review Triage Log

### Pass 1 (quick)

Verdicts: high 0, medium 1, low 0, false 0, maybe-false 0

| Finding | Verdict | Route | Evidence |
| --- | --- | --- | --- |
| `order.css` `.outcome::before` adds a label above `OrderOutcome.message()` | medium | bad_plan | The stylesheet sets `content` to Accepted, Hold, Limit, None left, or Check the form. A live result showed ACCEPTED above "The order is in process". The frozen rule says the visible outcome is exactly that sentence and is not prefixed. Design notes had asked for the kickers. |

### Pass 2 (quick)

Verdicts: high 0, medium 0, low 0, false 0, maybe-false 0

No findings.

## Design Notes

Night-drop desk. Left: kicker "Flash sale", headline "Take what's left.", three facts. Right: paper ticket. Button "Place order". Color the outcome sentence through `data-tone`. Do not add a label or any other generated text.

```html
<p th:if="${outcome != null}" id="outcome" class="outcome" role="status"
   th:attr="data-tone=${tone}" th:text="${outcome}"></p>
```

Tone in `OrderPage`: in process → `go`, wait → `wait`, refused → `stop`, sold out → `empty`, else `bad`. HTML `required` and `min="1"` may guide the browser. The server sentence still wins.

## Verification

**Commands:**
- `./gradlew test` -- expected: `OrderPoolEventTest` passes. Compose PostgreSQL and Kafka must already be up.

**Manual checks (if no CLI):**
- Open `http://localhost:8080/`, submit a user id and quantity, and confirm one inventory sentence, kept field values, and a stacked layout in a narrow window.
