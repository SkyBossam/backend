CREATE TABLE devices (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL, workspace_child_id uuid NOT NULL, child_id uuid NOT NULL,
 device_type text NOT NULL CHECK(device_type IN ('camera','sensor')), state text NOT NULL DEFAULT 'offline',
 last_seen_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(id,workspace_id,child_id),
 FOREIGN KEY(workspace_child_id,workspace_id,child_id) REFERENCES workspace_children(id,workspace_id,child_id)
);
CREATE TABLE monitoring_sessions (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL, child_id uuid NOT NULL, source_id uuid NOT NULL,
 status text NOT NULL CHECK(status IN ('live','offline','unverifiable')),
 safety jsonb NOT NULL DEFAULT '{}', started_at timestamptz NOT NULL, last_updated_at timestamptz NOT NULL,
 ended_at timestamptz, FOREIGN KEY(source_id,workspace_id,child_id) REFERENCES devices(id,workspace_id,child_id),
 CHECK(ended_at IS NULL OR ended_at>=started_at)
);
CREATE INDEX idx_monitor_recent ON monitoring_sessions(workspace_id,child_id,last_updated_at DESC);
CREATE TABLE sensor_readings (
 id uuid PRIMARY KEY, device_id uuid NOT NULL REFERENCES devices(id), metric text NOT NULL CHECK(metric IN ('temperature','humidity','noise')),
 value numeric NOT NULL, unit text NOT NULL, observed_at timestamptz NOT NULL
);
CREATE INDEX idx_sensor_recent ON sensor_readings(device_id,metric,observed_at DESC);
CREATE TABLE detection_events (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL, workspace_child_id uuid NOT NULL, child_id uuid NOT NULL, source_id uuid NOT NULL,
 type text NOT NULL CHECK(type IN ('cry','face_covered','edge_proximity','movement','camera_obstructed','connection_lost')),
 severity text NOT NULL CHECK(severity IN ('immediate','caution','stable','unknown')),
 status text NOT NULL CHECK(status IN ('new','acknowledged','checking','resolved','unverifiable')),
 started_at timestamptz NOT NULL, ended_at timestamptz, last_observed_at timestamptz NOT NULL,
 verifiable boolean NOT NULL, model_version text, confidence numeric CHECK(confidence BETWEEN 0 AND 1),
 summary text NOT NULL, recommendation_status text NOT NULL DEFAULT 'pending',
 UNIQUE(id,workspace_id), UNIQUE(id,workspace_id,child_id),
 FOREIGN KEY(workspace_child_id,workspace_id,child_id) REFERENCES workspace_children(id,workspace_id,child_id),
 FOREIGN KEY(source_id,workspace_id,child_id) REFERENCES devices(id,workspace_id,child_id),
 CHECK(ended_at IS NULL OR ended_at>=started_at)
);
CREATE INDEX idx_events_feed ON detection_events(workspace_id,child_id,started_at DESC,id DESC);
CREATE TABLE detection_receipts (
 source_id uuid NOT NULL REFERENCES devices(id), source_event_id text NOT NULL, event_id uuid NOT NULL REFERENCES detection_events(id),
 request_hash text NOT NULL, PRIMARY KEY(source_id,source_event_id)
);
CREATE TABLE event_status_history (
 id uuid PRIMARY KEY, event_id uuid NOT NULL REFERENCES detection_events(id), from_status text, to_status text NOT NULL,
 actor_id uuid REFERENCES users(id), reason text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE event_acknowledgements (
 id uuid PRIMARY KEY, event_id uuid NOT NULL, membership_id uuid NOT NULL, workspace_id uuid NOT NULL,
 acknowledged_at timestamptz NOT NULL DEFAULT now(), UNIQUE(event_id,membership_id),
 FOREIGN KEY(event_id,workspace_id) REFERENCES detection_events(id,workspace_id),
 FOREIGN KEY(membership_id,workspace_id) REFERENCES memberships(id,workspace_id)
);
CREATE TABLE event_resolutions (
 id uuid PRIMARY KEY, event_id uuid NOT NULL, membership_id uuid NOT NULL, workspace_id uuid NOT NULL,
 result text NOT NULL CHECK(result IN ('checked_safe','intervention_done','false_alarm','needs_help')),
 note text, resolved_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(event_id,workspace_id) REFERENCES detection_events(id,workspace_id),
 FOREIGN KEY(membership_id,workspace_id) REFERENCES memberships(id,workspace_id)
);
CREATE UNIQUE INDEX ux_final_resolution ON event_resolutions(event_id) WHERE result<>'needs_help';
CREATE TABLE cause_candidates (
 id uuid PRIMARY KEY, event_id uuid NOT NULL REFERENCES detection_events(id), cause_key text NOT NULL CHECK(cause_key IN ('hunger','sleepiness','discomfort','illness_check','other')),
 label text NOT NULL, rank integer NOT NULL, reason text NOT NULL, score numeric, UNIQUE(event_id,rank)
);
CREATE TABLE recommendations (
 id uuid PRIMARY KEY, event_id uuid NOT NULL REFERENCES detection_events(id), action_key text NOT NULL, title text NOT NULL,
 explanation text NOT NULL, instructions jsonb NOT NULL, priority integer NOT NULL, model_version text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(id,event_id), UNIQUE(event_id,action_key)
);
CREATE TABLE care_feedback (
 id uuid PRIMARY KEY, event_id uuid NOT NULL, membership_id uuid NOT NULL, workspace_id uuid NOT NULL,
 response text NOT NULL CHECK(response IN ('stopped','reduced','continues','unknown')), note text, idempotency_key text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(membership_id,idempotency_key), UNIQUE(event_id,membership_id), UNIQUE(id,event_id),
 FOREIGN KEY(event_id,workspace_id) REFERENCES detection_events(id,workspace_id),
 FOREIGN KEY(membership_id,workspace_id) REFERENCES memberships(id,workspace_id)
);
CREATE TABLE action_attempts (
 id uuid PRIMARY KEY, feedback_id uuid NOT NULL, event_id uuid NOT NULL, recommendation_id uuid NOT NULL,
 action_state text NOT NULL CHECK(action_state IN ('attempted','not_attempted')), note text,
 FOREIGN KEY(feedback_id,event_id) REFERENCES care_feedback(id,event_id),
 FOREIGN KEY(recommendation_id,event_id) REFERENCES recommendations(id,event_id), UNIQUE(feedback_id,recommendation_id)
);
CREATE OR REPLACE FUNCTION feedback_requires_cry() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NOT EXISTS(SELECT 1 FROM detection_events WHERE id=NEW.event_id AND type='cry') THEN RAISE EXCEPTION 'feedback requires cry event' USING ERRCODE='23514'; END IF; RETURN NEW; END $$;
CREATE TRIGGER trg_feedback_cry BEFORE INSERT ON care_feedback FOR EACH ROW EXECUTE FUNCTION feedback_requires_cry();
CREATE TABLE routines (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL, workspace_child_id uuid NOT NULL, child_id uuid NOT NULL,
 type text NOT NULL CHECK(type IN ('sleep','wake','play','soothe','custom')), title text NOT NULL, note text,
 scheduled_at timestamptz, ended_at timestamptz, timezone text NOT NULL DEFAULT 'Asia/Seoul',
 status text NOT NULL CHECK(status IN ('todo','in_progress','done')), completed_at timestamptz, created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz,
 FOREIGN KEY(workspace_child_id,workspace_id,child_id) REFERENCES workspace_children(id,workspace_id,child_id),
 FOREIGN KEY(created_by,workspace_id) REFERENCES memberships(id,workspace_id),
 CHECK(ended_at IS NULL OR (scheduled_at IS NOT NULL AND ended_at>=scheduled_at))
);
CREATE INDEX idx_routines_child ON routines(workspace_id,child_id,scheduled_at DESC,id DESC) WHERE deleted_at IS NULL;
CREATE TABLE stream_access_tokens (
 id uuid PRIMARY KEY, token_hash text NOT NULL UNIQUE, membership_id uuid NOT NULL, workspace_id uuid NOT NULL,
 child_id uuid NOT NULL, session_id uuid NOT NULL REFERENCES user_sessions(id), device_id uuid NOT NULL,
 expires_at timestamptz NOT NULL, revoked_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(membership_id,workspace_id) REFERENCES memberships(id,workspace_id),
 FOREIGN KEY(device_id,workspace_id,child_id) REFERENCES devices(id,workspace_id,child_id)
);
