CREATE TABLE item (
    id text PRIMARY KEY,
    pool integer NOT NULL
);

CREATE TABLE customer (
    user_id text PRIMARY KEY
);

INSERT INTO item (id, pool) VALUES ('item-1', 10);
