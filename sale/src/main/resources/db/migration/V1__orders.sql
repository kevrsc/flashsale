CREATE TABLE orders (
    qualification_id text PRIMARY KEY,
    sale_id text NOT NULL,
    user_id text NOT NULL,
    item_id text NOT NULL
);

CREATE TABLE qualification_report_sent (
    qualification_id text PRIMARY KEY
);
