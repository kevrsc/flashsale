CREATE TABLE item (
    id text PRIMARY KEY,
    quantity integer NOT NULL
);

CREATE TABLE customer (
    user_id text PRIMARY KEY
);

INSERT INTO item (id, quantity) VALUES ('item-1', 10);
