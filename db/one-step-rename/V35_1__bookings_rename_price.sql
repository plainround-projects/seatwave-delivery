-- The wrong way, kept only so that RenameUnderLoadCli can measure it: the rename in one release.
-- It takes a brief lock and then every pod still running the previous release names a column that
-- no longer exists, until the rollout has replaced it. Never run by the pipeline.
SET lock_timeout = '2s';
ALTER TABLE bookings RENAME COLUMN price TO price_paid;
