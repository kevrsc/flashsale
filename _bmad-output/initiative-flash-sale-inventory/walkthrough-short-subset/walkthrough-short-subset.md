# A short pool keeps a subset

Current block: review finished

## Blocks

- [x] **1. Intent** — done
- [x] **2. Broad strokes** — done
- [x] **3. The smaller number** — done
- [x] **4. The tests that used to drop the order** — done
- [x] **5. A missed update** — done
- [x] **6. Periphery** — done

## 1. Intent

**Problem:** A request larger than the quantity, or larger than the units still left under 3, is dropped even when at least one unit can be taken. The customer loses that place.

**Approach:** On the locked transaction, deduct the smaller of the request, the quantity, and the units left, when that number is at least one. The page says the order is in process, the customer row and the order event record that smaller number, and the wait is stored again. No units left, after the hour, with the quantity above zero, stays refused.

Taken verbatim from the Intent section of [the story plan](../epic-local-flash-sale-inventory/story-a-short-pool-or-a-short-limit-keeps-a-subset-plan.md).

## 2. Broad strokes

After the one-hour wait and the refusal for no units left, the order takes the smaller of the request, the quantity, and the units still left. That number is what the customer row and the order event record.

Open these in order:

- [Orders.java](../../../src/main/java/com/flashsale/inventory/Orders.java) — chooses that smaller number inside the locked transaction
- [shortQuantityKeepsThatQuantity](../../../src/test/java/com/flashsale/OrderPoolEventTest.java) — two units left in the quantity, a request for three, and the event is for two
- [shortLimitAfterTheHourKeepsTheUnitsLeft](../../../src/test/java/com/flashsale/OrderPoolEventTest.java) — one unit left under the limit of 3, and the event is for that one
- [bothShortKeepsTheSmallerOfQuantityAndUnitsLeft](../../../src/test/java/com/flashsale/OrderPoolEventTest.java) — both the quantity and the units left are short, and the event is for the smaller of those two

## 3. The smaller number

The customer row is still locked first, then the item. A customer in the wait is told to wait. A customer with no units left, after that hour, with the quantity above zero, is still refused. Only then does the order take `min(request, quantity, units left)`.

That number has to be at least 1. The conditional update subtracts it from `item-1` only while the row still holds it. On a match, `units_ordered` grows by that number, `next_allowed` becomes PostgreSQL's clock plus one hour, and `unitsDeducted` in the event is that number, not the request.

- [Orders.java:51](../../../src/main/java/com/flashsale/inventory/Orders.java) — the wait, then the refusal
- [Orders.java:62](../../../src/main/java/com/flashsale/inventory/Orders.java) — the smaller number, the update, and the event

## 4. The tests that used to drop the order

Three tests used to expect a blank page and no deduction when the request did not fit. They now expect the order in process for the smaller number.

- [OrderPoolEventTest.java:264](../../../src/test/java/com/flashsale/OrderPoolEventTest.java) — quantity 2, request 3, event for 2, quantity ends at 0
- [OrderPoolEventTest.java:281](../../../src/test/java/com/flashsale/OrderPoolEventTest.java) — two units already ordered, the hour moved into the past, request 2, event for the 1 unit left
- [OrderPoolEventTest.java:302](../../../src/test/java/com/flashsale/OrderPoolEventTest.java) — one unit left under the limit and two in the quantity, request 4, event for 1

The refusal when nothing is left, and the empty quantity with no sold-out words, are the same tests as before.

## 5. A missed update

If the conditional update changes no row and a re-read of the locked item shows a different quantity still above zero, the smaller number is computed again and the update is tried again. The same quantity, a quantity that is no longer above zero, or a recomputed number below 1 stops the loop. That stop rolls back and does not say sold out.

- [Orders.java:83](../../../src/main/java/com/flashsale/inventory/Orders.java) — the re-read and the stop
- [Orders.java:95](../../../src/main/java/com/flashsale/inventory/Orders.java) — the re-read of `item-1`

The item row is already locked, so another transaction cannot change the quantity between the read and the update. This branch is not what the tests exercise.

## 6. Periphery

- [story plan](../epic-local-flash-sale-inventory/story-a-short-pool-or-a-short-limit-keeps-a-subset-plan.md) — the approved intent, still status built
