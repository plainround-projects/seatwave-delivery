-- Release 1. The copy runs as a job after v2 (which writes both columns) is on every pod, never
-- inside this migration: a procedure can commit each batch, so no long transaction holds row
-- locks or bloats the table. The pipeline calls it until it reports 0 rows left.
CREATE PROCEDURE backfill_price_paid(batch_rows INTEGER, pause_seconds NUMERIC)
LANGUAGE plpgsql
AS $$
DECLARE
  copied INTEGER;
BEGIN
  LOOP
    UPDATE bookings
       SET price_paid = price
     WHERE booking_id IN (SELECT booking_id
                            FROM bookings
                           WHERE price_paid IS NULL
                           LIMIT batch_rows
                             FOR UPDATE SKIP LOCKED);
    GET DIAGNOSTICS copied = ROW_COUNT;
    RAISE NOTICE 'copied % rows', copied;
    EXIT WHEN copied = 0;
    COMMIT;
    PERFORM pg_sleep(pause_seconds);
  END LOOP;
END;
$$;
