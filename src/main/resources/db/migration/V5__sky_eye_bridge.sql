CREATE TABLE sky_eye_status (
 device_id uuid PRIMARY KEY REFERENCES devices(id), observed_at timestamptz NOT NULL,
 received_at timestamptz NOT NULL DEFAULT now(), body jsonb NOT NULL
);
CREATE TABLE sky_eye_events (
 device_id uuid NOT NULL REFERENCES devices(id), event_id varchar(128) NOT NULL,
 observed_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(),
 body jsonb NOT NULL, PRIMARY KEY(device_id,event_id)
);
CREATE INDEX idx_sky_eye_events_time ON sky_eye_events(device_id,observed_at DESC);
CREATE TABLE sky_eye_commands (
 id uuid PRIMARY KEY, device_id uuid NOT NULL REFERENCES devices(id), episode_uid uuid NOT NULL,
 action varchar(16) NOT NULL, status varchar(16) NOT NULL DEFAULT 'queued',
 created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL,
 completed_at timestamptz, UNIQUE(device_id,episode_uid)
);
