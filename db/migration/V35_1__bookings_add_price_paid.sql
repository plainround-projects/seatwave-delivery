-- Release 1, expand. A nullable column with no default changes only the catalog: no table rewrite,
-- and the strong lock it needs lasts milliseconds. lock_timeout makes it give up, not queue
-- every booking query behind it, if a long transaction holds the table (Module 13).
SET lock_timeout = '2s';
ALTER TABLE bookings ADD COLUMN price_paid NUMERIC(12, 2);
COMMENT ON COLUMN bookings.price_paid IS 'what the fan paid; replaces price, which read like sections.price';
