-- Run only while ALL old/new Java apps are stopped, after notification Flyway V1 initialized.
-- Source exports are created inside the same PostgreSQL container. No original tables are deleted.
BEGIN;
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM user_notification) OR EXISTS (SELECT 1 FROM consumed_payment_event)
    OR EXISTS (SELECT 1 FROM dead_letter_event) THEN
  RAISE EXCEPTION 'Destination must be empty; inspect prior cutover rather than deleting data';
 END IF;
END $$;
COPY consumed_payment_event FROM '/tmp/offlinepay-m5-consumed.csv' WITH CSV;
COPY user_notification FROM '/tmp/offlinepay-m5-notices.csv' WITH CSV;
COPY dead_letter_event FROM '/tmp/offlinepay-m5-dlt.csv' WITH CSV;
COMMIT;
