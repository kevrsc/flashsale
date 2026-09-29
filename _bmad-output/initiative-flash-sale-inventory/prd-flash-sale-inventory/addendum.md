# Addendum: Flash-sale inventory

Landscape notes for later recommendations. These are not product decisions and are not requirements until the user adopts them.

## Industry patterns

- **Virtual waiting room.** Ticketmaster’s waiting room / Smart Queue, Queue-it, and Salesforce’s hype-sale guidance hold shoppers off the store until admitted in a controlled flow. Shoppers see a countdown or a place in line, then a short shopping window; the item is not guaranteed. Meant to stop a site stampede and chaotic refresh traffic, not to make the stock count correct by itself. Sources: Ticketmaster, Queue-it, Salesforce traffic management.
- **Pre-queue randomization, then FIFO.** For a scheduled drop, early arrivals wait on a countdown and are shuffled when the sale starts so on-time visitors have an equal chance; late arrivals go to the back. Pure first-in-first-out is criticized as a millisecond race that favors fast networks. Source: Queue-it.
- **Draw / raffle / invite allocation.** Sneaker drops (Nike SNKRS FLOW is first-come-first-served; DAN and LEO are short equal-chance draws) and raffle or reservation flows let only winners into checkout. Hybrids split stock across loyalty, public draw, and in-store appointments. Meant to stop stampedes and speed-based unfairness; conversion is delayed and a loss can feel opaque. Sources: Queue-it, Sole Retriever on SNKRS.
- **Soft reservation / checkout hold.** A unit is held only for a short window, then released if unpaid. Shopify holds at payment start, not add-to-cart. Amazon Lightning Deals give 15 minutes after add-to-cart, then a waitlist if every unit is sitting in a cart. Ticketmaster uses a demand-based checkout timer. Salesforce advises against reserving at add-to-cart because abandoned carts lock stock. Sources: Shopify engineering, Amazon Lightning Deals help, Salesforce hype checklist.
- **Reserve, then claim, with release as compensation.** Mark units unavailable for the attempt, confirm only after payment, put them back if payment fails or the hold expires. The product choice is two misses: a paid oversell that becomes a cancellation, or a false sold-out that loses a real sale. Source: Shopify engineering.
- **Oversell, then cancel or backorder.** Shopify’s “Continue selling when out of stock” is positioned for preorder and backorder, not a hard drop. Marketplace sellers who accept an order and cancel later hit account-health targets (Amazon seller-fulfilled cancellation-rate target under 2.5%). Sources: Shopify Help, Amazon order-performance policy.
- **Ring-fencing / channel allocation, plus a safety buffer.** A protected pool is assigned to the drop, marketplace, wholesale, or store so one channel cannot spend another’s units. A buffer can hide the last units on purpose. Fences that are too tight strand sellable units. Sources: OnePint, Uphance.
- **One available-to-sell number across web and store.** Separate online and door counts are treated as the failure that sells the same unit twice. Reservations reduce what is available to order and expire if not fulfilled; under heavy load the release can lag, so availability stays briefly wrong. Source: Salesforce inventory reservation.

## Still commonly criticized

Waiting rooms do not stop oversell. Pages stay briefly optimistic, so “in stock” dies at checkout. Unpublished queue or draw rules feel rigged. Per-person limits break across guest checkout, multiple accounts, and channels. Slow release of abandoned holds undersells. A successful payment and a stock deduction that are not one outcome produce either a cancellation or ghost stock. Shoppers dislike both a cancelled paid order and a timer that expires while they are still checking out.

## Questions held for elicitation

1. Which miss is unacceptable: a paid order that must be cancelled, or refusing a buyer when a unit was actually available?
2. What does fair access mean: first to click, equal chance among people who arrived on time, or priority for members and invites? Is that same pool also sold in store and on marketplaces?
3. When should a unit become unavailable to everyone else: add to cart, start of checkout, or successful payment? What should the shopper see if they lose it?

## User-stated mechanism

Captured here so the PRD can stay at capability level.

- The product includes a landing page where a customer orders the item. The PRD uses "order," not "reserve."
- A successful order deducts units from the inventory pool for the earliest successful orders while the pool remains above zero.
- Order processing and related functions are separate services.
- On a successful order, the service deducts inventory in the database and sends an order event. The stated mechanism is a Kafka client that performs that database write and posts the order event to a Kafka topic so other services can continue the order.
- Shopper-facing responses named so far: order is in process; wait one hour before ordering again; the item has sold out.
- The one-hour rule is a wait before that person can order again. It replaces the earlier reading that the block applied only during the first hour of the sale.
- A single order can include more than one unit. The customer limit of 3 is the total units in that customer's successful orders, reached in one order or across two or three orders.
- A short inventory pool does not ignore the customer limit. The units deducted are the smaller of the units asked for, the units the pool holds, and the units the customer has left under the customer limit.
- When the inventory pool could cover the full request but the full order would pass the customer limit, the order is accepted for the units the customer has left. This replaces the earlier whole-order refusal for that case. A customer with no units left is still refused.
- A customer has an account. The service recognizes the customer by that account's user ID. Creating and managing accounts is outside this service.

## Robustness, as stated

- No order deducts a unit that was not actually available. The earlier wording was "reserved"; the PRD now says order.
- The service's own resources must handle a high volume of requests. Database contention has to be considered. The approach (locking, isolation, buffering, or otherwise) is not chosen here.
