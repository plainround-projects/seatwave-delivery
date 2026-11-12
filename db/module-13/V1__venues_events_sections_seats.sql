-- SeatWave's catalog: where an event happens, what it sells and at what price (Module 13).
-- Rule of this schema: one fact in one place. A seat row says only where a seat is; whether it is
-- held or sold is a fact about a hold or a booking, so it lives in those tables (V2).

CREATE TABLE venues (
  venue_id BIGINT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  CONSTRAINT venue_name_not_blank CHECK (btrim(name) <> '')
);

-- An event happens at one venue; a venue hosts many events.
CREATE TABLE events (
  event_id BIGINT PRIMARY KEY,
  venue_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  -- An absolute instant, stored in UTC; Module 10 changed Module 1's epoch milliseconds to this.
  starts_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT event_at_a_real_venue FOREIGN KEY (venue_id) REFERENCES venues (venue_id),
  CONSTRAINT event_name_not_blank CHECK (btrim(name) <> '')
);

-- A section belongs to one event, not to the venue: the same arena is set up differently for a
-- concert with a stage than for a match, so each event carries its own plan. The price is a fact
-- about the section, so it is stored here once, never on each seat.
CREATE TABLE sections (
  event_id BIGINT NOT NULL,
  section_code VARCHAR(4) NOT NULL,
  -- Money as an exact decimal, never a floating-point number.
  price NUMERIC(12, 2) NOT NULL,
  currency CHAR(3) NOT NULL,
  CONSTRAINT sections_pkey PRIMARY KEY (event_id, section_code),
  CONSTRAINT section_of_a_real_event FOREIGN KEY (event_id) REFERENCES events (event_id),
  CONSTRAINT section_code_format CHECK (section_code ~ '^[A-Z]{1,4}$'),
  CONSTRAINT price_not_negative CHECK (price >= 0),
  CONSTRAINT currency_format CHECK (currency ~ '^[A-Z]{3}$')
);

-- One row per sellable seat of an event. The key (event_id, seat_label) is Module 1's, kept so
-- every earlier module's queries still name a seat the same way.
CREATE TABLE seats (
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  section_code VARCHAR(4) NOT NULL,
  seat_number INT NOT NULL,
  CONSTRAINT seats_pkey PRIMARY KEY (event_id, seat_label),
  CONSTRAINT seat_in_a_real_section FOREIGN KEY (event_id, section_code)
    REFERENCES sections (event_id, section_code),
  CONSTRAINT seat_number_positive CHECK (seat_number > 0),
  -- The label repeats the section and the number (a deliberate copy, Chapter 13.2); this check
  -- makes the copy impossible to get wrong.
  CONSTRAINT seat_label_matches CHECK (seat_label = section_code || '-' || seat_number),
  CONSTRAINT one_seat_per_number UNIQUE (event_id, section_code, seat_number)
);
