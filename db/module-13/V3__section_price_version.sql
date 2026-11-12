-- Expand only (Module 13, Section 6): a version column for optimistic price edits by the box
-- office. Code written for V2 ignores the column and keeps working; the default fills every
-- existing row in the same statement, so no row is ever without a version.
ALTER TABLE sections ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE sections ADD CONSTRAINT version_not_negative CHECK (version >= 0);
