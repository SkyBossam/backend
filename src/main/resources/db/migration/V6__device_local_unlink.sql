-- A local unlink requires a single-use identity proof bound to one approval.
ALTER TABLE device_challenges DROP CONSTRAINT device_challenges_purpose_check;
ALTER TABLE device_challenges ADD COLUMN target_device_id uuid;
ALTER TABLE device_challenges ADD CONSTRAINT device_challenges_purpose_check CHECK (purpose IN ('pair','authenticate','unlink'));
ALTER TABLE device_challenges ADD CONSTRAINT device_challenges_unlink_target_check CHECK ((purpose='unlink')=(target_device_id IS NOT NULL));
