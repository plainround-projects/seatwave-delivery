-- Release 4, contract: only after v4 has run everywhere for a full release and no version that
-- reads or writes price can come back. From here a rollback to v3 is impossible; fixes roll forward.
SET lock_timeout = '2s';
DROP PROCEDURE backfill_price_paid(INTEGER, NUMERIC);
ALTER TABLE bookings DROP COLUMN price;
