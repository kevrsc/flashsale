CREATE TABLE accepted_order (
    order_id text PRIMARY KEY,
    user_id text NOT NULL,
    item_id text NOT NULL,
    units_deducted integer NOT NULL,
    released boolean NOT NULL
);
