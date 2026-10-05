ALTER TABLE customer
    ADD COLUMN units_ordered integer NOT NULL DEFAULT 0,
    ADD COLUMN next_allowed timestamp;
