# Glossary

Terms for Flash-sale inventory. The kernel uses these words and no substitutes.

- **Customer** — A person with an account. The service recognizes the customer by that account's user ID from one order to the next.
- **User ID** — The identifier of a customer's account. The service uses it to tell one customer from another. Comparison is exact: no trim and no case fold.
- **Item** — The one limited product a customer orders.
- **Inventory pool** — The units of the item still available to order. This is the only count of units still available.
- **Order** — A customer's request, from the landing page, to purchase one or more units of the item.
- **Successful order** — An order that deducts units from the inventory pool and sends an order event. The customer sees that the order is in process.
- **Order event** — The notice this service sends, after a successful order, so other services can continue the purchase. It carries the units deducted.
- **Sold out** — The response when the inventory pool is not greater than zero. Nothing is deducted and no order event is sent. This response is shown even when the customer is also in the one-hour wait.
- **One-hour wait** — After a successful order, that customer must wait one hour before ordering again while the inventory pool is above zero.
- **Customer limit** — 3. The total number of units in successful orders by a single customer. Those units may be placed in one order or across two or three orders.
- **Refused** — The response when the customer has no units left under the customer limit, the inventory pool is greater than zero, and the customer is not in the one-hour wait. Nothing is deducted and no order event is sent.
- **Not accepted** — The response when the user ID is blank or the quantity is not a positive integer. Nothing is deducted and no order event is sent.
