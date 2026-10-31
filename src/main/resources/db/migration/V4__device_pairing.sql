-- A persistent software identity survives reboot; each approved child binding gets a new device row.
CREATE TABLE device_identities (
 id uuid PRIMARY KEY, public_key text NOT NULL, public_key_hash text NOT NULL UNIQUE,
 active_device_id uuid REFERENCES devices(id), created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE devices ADD COLUMN identity_id uuid REFERENCES device_identities(id);
ALTER TABLE devices ADD COLUMN display_name text NOT NULL DEFAULT '아이쿠 카메라';
ALTER TABLE devices ADD COLUMN paired_by uuid REFERENCES users(id);
ALTER TABLE devices ADD COLUMN revoked_at timestamptz;
CREATE UNIQUE INDEX ux_device_active_identity ON devices(identity_id) WHERE identity_id IS NOT NULL AND revoked_at IS NULL;

CREATE TABLE device_challenges (
 id uuid PRIMARY KEY, public_key_hash text NOT NULL, purpose text NOT NULL CHECK(purpose IN ('pair','authenticate')),
 nonce_hash text NOT NULL, expires_at timestamptz NOT NULL, used_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_device_challenges_expiry ON device_challenges(expires_at);
CREATE TABLE device_pairings (
 id uuid PRIMARY KEY, identity_id uuid NOT NULL REFERENCES device_identities(id), user_code_hash text NOT NULL UNIQUE,
 device_code_hash text NOT NULL UNIQUE, status text NOT NULL CHECK(status IN ('pending','approved','consumed','cancelled')),
 expires_at timestamptz NOT NULL, approved_by uuid REFERENCES users(id), device_id uuid REFERENCES devices(id),
 credential_id uuid, approved_at timestamptz, last_polled_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_device_pairings_identity ON device_pairings(identity_id,created_at DESC);
CREATE TABLE device_credentials (
 id uuid PRIMARY KEY, device_id uuid NOT NULL REFERENCES devices(id), token_hash text NOT NULL UNIQUE,
 expires_at timestamptz NOT NULL, revoked_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE device_pairings ADD CONSTRAINT fk_pairing_credential FOREIGN KEY(credential_id) REFERENCES device_credentials(id);
CREATE INDEX idx_device_credentials_device ON device_credentials(device_id) WHERE revoked_at IS NULL;
