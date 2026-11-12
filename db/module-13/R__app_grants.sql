-- The application's role may read and write SeatWave's rows and nothing else: no DELETE (a hold
-- or a booking is never deleted, only ended), no DDL, and no access to Flyway's history table.
-- Flyway runs this after the versioned migrations, and again whenever this file changes.
GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${appRole};
GRANT SELECT ON venues, events, sections, seats TO ${appRole};
-- UPDATE on sections is for the box office's price edits; on customers it is what lets the hold
-- trigger lock the customer's row (SELECT ... FOR UPDATE needs the UPDATE privilege).
GRANT UPDATE (price, version) ON sections TO ${appRole};
GRANT SELECT, INSERT, UPDATE ON customers, holds, bookings TO ${appRole};
GRANT USAGE ON SEQUENCE customers_customer_id_seq TO ${appRole};
