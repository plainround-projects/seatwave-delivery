-- Who holds and who bought (Module 13). The invariant of the whole course lives here:
-- a seat has at most one active hold or booking, and a customer has at most four active holds.

CREATE TABLE customers (
  customer_id BIGINT GENERATED ALWAYS AS IDENTITY,
  -- Module 7's customer string, for example fan_1; the number is the key other tables use.
  customer_ref VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT customers_pkey PRIMARY KEY (customer_id),
  CONSTRAINT one_customer_per_ref UNIQUE (customer_ref),
  CONSTRAINT customer_ref_format CHECK (customer_ref ~ '^[A-Za-z0-9_-]{1,64}$')
);

-- Every hold ever granted, kept after it ends: a regulated venue must be able to show who held
-- which seat and when. A hold moves ACTIVE -> RELEASED, EXPIRED or CONFIRMED, and never back.
CREATE TABLE holds (
  hold_id UUID NOT NULL,
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  customer_id BIGINT NOT NULL,
  state VARCHAR(10) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  ended_at TIMESTAMPTZ,
  CONSTRAINT holds_pkey PRIMARY KEY (hold_id),
  CONSTRAINT hold_names_a_real_seat FOREIGN KEY (event_id, seat_label)
    REFERENCES seats (event_id, seat_label),
  CONSTRAINT hold_by_a_real_customer FOREIGN KEY (customer_id) REFERENCES customers (customer_id),
  CONSTRAINT known_hold_state CHECK (state IN ('ACTIVE', 'RELEASED', 'EXPIRED', 'CONFIRMED')),
  CONSTRAINT hold_expires_after_it_starts CHECK (expires_at > created_at),
  -- An active hold has not ended; every other state has an end time.
  CONSTRAINT only_active_holds_are_open CHECK ((state = 'ACTIVE') = (ended_at IS NULL)),
  CONSTRAINT hold_ends_after_it_starts CHECK (ended_at IS NULL OR ended_at >= created_at),
  -- The target of the booking's foreign key below, which proves the booking's copies match.
  CONSTRAINT hold_identity UNIQUE (hold_id, event_id, seat_label, customer_id)
);

-- Invariant, part 1: at most one claim per seat. A claim is an ACTIVE hold or a CONFIRMED one
-- (a booking). Expiry is lazy: an ACTIVE hold past expires_at still occupies the index until the
-- next hold on that seat marks it EXPIRED in the same transaction. The index, not Java, decides:
-- two transactions inserting a claim for one seat cannot both commit, whatever the code does.
CREATE UNIQUE INDEX one_claim_per_seat ON holds (event_id, seat_label)
  WHERE state IN ('ACTIVE', 'CONFIRMED');

-- The limit check below reads a customer's active holds on every insert; without this index it
-- would scan every hold. Module 14 explains how indexes are chosen.
CREATE INDEX active_holds_by_customer ON holds (customer_id) WHERE state = 'ACTIVE';

-- Invariant, part 2: at most four active holds per customer. A CHECK constraint sees one row and
-- a unique index compares one key, so a rule over many rows needs a lock: this trigger locks the
-- customer's row, then counts. Every hold of one customer therefore waits its turn, and the count
-- it reads is fresh, because at read committed each statement of a VOLATILE function takes a new
-- snapshot. At repeatable read the count would come from the transaction's first snapshot and
-- could be stale, so that level is refused outright. At serializable the database itself detects
-- two concurrent counts that both miss each other's insert.
CREATE FUNCTION enforce_hold_limit() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  hold_limit CONSTANT INT := TG_ARGV[0]::INT;
  active_holds INT;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    -- A hold never becomes active again, and never moves to another customer.
    IF OLD.state <> 'ACTIVE' OR OLD.customer_id <> NEW.customer_id THEN
      RAISE EXCEPTION 'hold % cannot become active again or change customer', NEW.hold_id
        USING ERRCODE = 'check_violation', CONSTRAINT = 'hold_limit_per_customer';
    END IF;
    RETURN NEW;
  END IF;
  IF current_setting('transaction_isolation') = 'repeatable read' THEN
    RAISE EXCEPTION 'holds are written at read committed or serializable, not repeatable read'
      USING ERRCODE = 'feature_not_supported';
  END IF;
  PERFORM 1 FROM customers WHERE customer_id = NEW.customer_id FOR UPDATE;
  -- The new hold's own creation time is "now": the same clock the code used for expires_at.
  SELECT count(*) INTO active_holds FROM holds
    WHERE customer_id = NEW.customer_id AND state = 'ACTIVE' AND expires_at > NEW.created_at;
  IF active_holds >= hold_limit THEN
    RAISE EXCEPTION 'customer % already has % active holds', NEW.customer_id, active_holds
      USING ERRCODE = 'check_violation', CONSTRAINT = 'hold_limit_per_customer';
  END IF;
  RETURN NEW;
END;
$$;

-- The argument is the limit: 4 active holds per customer, the rule of Module 13's brief.
CREATE TRIGGER hold_limit_per_customer
  BEFORE INSERT OR UPDATE OF state, customer_id ON holds
  FOR EACH ROW WHEN (NEW.state = 'ACTIVE')
  EXECUTE FUNCTION enforce_hold_limit('4');

-- One row per sold seat. event_id, seat_label and customer_id repeat the hold's values (a
-- deliberate copy, Chapter 13.2): one_booking_per_seat needs the seat in this table, and the
-- composite foreign key proves the copy equals the hold. price and currency are not a copy: they
-- record what was paid, which must not change when the section's price changes later.
CREATE TABLE bookings (
  booking_id UUID NOT NULL,
  hold_id UUID NOT NULL,
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  customer_id BIGINT NOT NULL,
  price NUMERIC(12, 2) NOT NULL,
  currency CHAR(3) NOT NULL,
  confirmed_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT bookings_pkey PRIMARY KEY (booking_id),
  CONSTRAINT one_booking_per_hold UNIQUE (hold_id),
  -- Module 1's second guard, kept: even a bug in the confirm cannot sell one seat twice.
  CONSTRAINT one_booking_per_seat UNIQUE (event_id, seat_label),
  CONSTRAINT booking_matches_its_hold FOREIGN KEY (hold_id, event_id, seat_label, customer_id)
    REFERENCES holds (hold_id, event_id, seat_label, customer_id),
  CONSTRAINT paid_price_not_negative CHECK (price >= 0),
  CONSTRAINT paid_currency_format CHECK (currency ~ '^[A-Z]{3}$')
);
