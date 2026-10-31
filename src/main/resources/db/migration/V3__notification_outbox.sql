CREATE TABLE notification_preferences (
 membership_id uuid PRIMARY KEY REFERENCES memberships(id), cry_sound boolean NOT NULL DEFAULT true,
 push_enabled boolean NOT NULL DEFAULT false, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE notifications (
 id uuid PRIMARY KEY, event_id uuid NOT NULL, workspace_id uuid NOT NULL, recipient_membership_id uuid NOT NULL,
 channel text NOT NULL CHECK(channel IN ('in_app','push')), delivery_status text NOT NULL CHECK(delivery_status IN ('queued','sent','failed')),
 read_at timestamptz, sent_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(event_id,recipient_membership_id,channel),
 FOREIGN KEY(event_id,workspace_id) REFERENCES detection_events(id,workspace_id),
 FOREIGN KEY(recipient_membership_id,workspace_id) REFERENCES memberships(id,workspace_id)
);
CREATE INDEX idx_notifications_member ON notifications(recipient_membership_id,created_at DESC,id DESC);
CREATE TABLE outbox_messages (
 id uuid PRIMARY KEY, topic text NOT NULL, aggregate_id uuid NOT NULL, workspace_id uuid NOT NULL REFERENCES workspaces(id),
 payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz,
 retry_count integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now(), dead_lettered_at timestamptz, last_error_code text
);
CREATE INDEX idx_outbox_pending ON outbox_messages(next_attempt_at,created_at) WHERE published_at IS NULL AND dead_lettered_at IS NULL;
CREATE TABLE realtime_messages (
 sequence bigserial PRIMARY KEY, message_id uuid NOT NULL UNIQUE, outbox_id uuid NOT NULL REFERENCES outbox_messages(id),
 membership_id uuid NOT NULL, workspace_id uuid NOT NULL, child_id uuid NOT NULL, event_id uuid NOT NULL,
 type text NOT NULL, severity text NOT NULL, occurred_at timestamptz NOT NULL,
 UNIQUE(outbox_id,membership_id),
 FOREIGN KEY(membership_id,workspace_id) REFERENCES memberships(id,workspace_id),
 FOREIGN KEY(event_id,workspace_id,child_id) REFERENCES detection_events(id,workspace_id,child_id)
);
CREATE INDEX idx_realtime_member ON realtime_messages(membership_id,sequence);
CREATE TABLE privacy_requests (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), workspace_id uuid NOT NULL REFERENCES workspaces(id),
 type text NOT NULL CHECK(type IN ('export','delete')), status text NOT NULL DEFAULT 'pending', created_at timestamptz NOT NULL DEFAULT now()
);
