-- Release 2, after the backfill reported 0 rows. NOT VALID adds the rule for new rows at once and
-- checks no old row, so the strong lock is brief.
SET lock_timeout = '2s';
ALTER TABLE bookings ADD CONSTRAINT price_paid_present CHECK (price_paid IS NOT NULL) NOT VALID;
