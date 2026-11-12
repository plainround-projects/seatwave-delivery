-- Release 2. VALIDATE scans the table under a lock that lets reads and writes go on. With a valid
-- CHECK proving no NULL, SET NOT NULL skips its own scan (PostgreSQL 12 and later). price stops
-- being required, so v4, which no longer writes it, can insert.
SET lock_timeout = '2s';
ALTER TABLE bookings VALIDATE CONSTRAINT price_paid_present;
ALTER TABLE bookings ALTER COLUMN price_paid SET NOT NULL;
ALTER TABLE bookings DROP CONSTRAINT price_paid_present;
ALTER TABLE bookings ALTER COLUMN price DROP NOT NULL;
