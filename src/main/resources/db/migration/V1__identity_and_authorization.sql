CREATE TABLE users (
 id uuid PRIMARY KEY, email text NOT NULL UNIQUE, password_hash text NOT NULL,
 email_verified boolean NOT NULL DEFAULT false, status text NOT NULL DEFAULT 'active' CHECK(status IN ('active','disabled','deleted')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE user_sessions (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), family_id uuid NOT NULL,
 token_hash text NOT NULL UNIQUE, expires_at timestamptz NOT NULL, revoked_at timestamptz,
 replaced_by uuid REFERENCES user_sessions(id), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_sessions_user ON user_sessions(user_id, family_id);
CREATE TABLE email_verifications (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), token_hash text NOT NULL UNIQUE,
 expires_at timestamptz NOT NULL, consumed_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE rate_limits (bucket text PRIMARY KEY, window_start timestamptz NOT NULL, attempts integer NOT NULL);
CREATE TABLE workspaces (
 id uuid PRIMARY KEY, type text NOT NULL CHECK(type IN ('PERSONAL','INSTITUTION')), name text NOT NULL,
 status text NOT NULL DEFAULT 'active', created_by uuid NOT NULL REFERENCES users(id), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE memberships (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id), user_id uuid NOT NULL REFERENCES users(id),
 role text NOT NULL CHECK(role IN ('owner','admin','guardian','sitter','staff','viewer')),
 status text NOT NULL DEFAULT 'active', created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,user_id), UNIQUE(id,workspace_id)
);
CREATE TABLE children (
 id uuid PRIMARY KEY, display_name text NOT NULL, birth_date date NOT NULL, photo_object_key text,
 created_by uuid NOT NULL REFERENCES users(id), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE data_share_grants (
 id uuid PRIMARY KEY, child_id uuid NOT NULL REFERENCES children(id), from_workspace_id uuid NOT NULL REFERENCES workspaces(id),
 to_workspace_id uuid NOT NULL REFERENCES workspaces(id), scopes jsonb NOT NULL CHECK(jsonb_typeof(scopes)='array'),
 consent_ref text NOT NULL, created_by uuid NOT NULL REFERENCES users(id), expires_at timestamptz NOT NULL,
 revoked_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(), CHECK(from_workspace_id<>to_workspace_id),
 CHECK(expires_at>created_at), UNIQUE(id,to_workspace_id,child_id)
);
CREATE TABLE workspace_children (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id), child_id uuid NOT NULL REFERENCES children(id),
 link_status text NOT NULL DEFAULT 'active' CHECK(link_status IN ('active','ended')), share_grant_id uuid,
 created_at timestamptz NOT NULL DEFAULT now(), ended_at timestamptz,
 UNIQUE(id,workspace_id), UNIQUE(id,child_id), UNIQUE(id,workspace_id,child_id),
 FOREIGN KEY(share_grant_id,workspace_id,child_id) REFERENCES data_share_grants(id,to_workspace_id,child_id)
);
CREATE UNIQUE INDEX ux_workspace_child_active ON workspace_children(workspace_id,child_id) WHERE link_status='active';
CREATE TABLE child_access_grants (
 id uuid PRIMARY KEY, workspace_child_id uuid NOT NULL, membership_id uuid NOT NULL, workspace_id uuid NOT NULL,
 capabilities jsonb NOT NULL CHECK(jsonb_typeof(capabilities)='array'), valid_from timestamptz NOT NULL,
 valid_until timestamptz, revoked_at timestamptz, invitation_id uuid,
 FOREIGN KEY(workspace_child_id,workspace_id) REFERENCES workspace_children(id,workspace_id),
 FOREIGN KEY(membership_id,workspace_id) REFERENCES memberships(id,workspace_id), CHECK(valid_until IS NULL OR valid_until>valid_from)
);
CREATE INDEX idx_grants_member ON child_access_grants(membership_id,workspace_child_id);
CREATE TABLE invitations (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id), invited_email_hash text NOT NULL,
 target_role text NOT NULL CHECK(target_role IN ('admin','guardian','sitter','staff','viewer')), token_hash text NOT NULL UNIQUE,
 expires_at timestamptz NOT NULL, grant_valid_from timestamptz NOT NULL, grant_valid_until timestamptz NOT NULL,
 accepted_at timestamptz, accepted_by uuid REFERENCES users(id), revoked_at timestamptz,
 created_by uuid NOT NULL REFERENCES users(id), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(id,workspace_id), CHECK(grant_valid_until>grant_valid_from)
);
ALTER TABLE child_access_grants ADD CONSTRAINT fk_grants_invitation FOREIGN KEY(invitation_id) REFERENCES invitations(id);
CREATE TABLE invitation_child_scopes (
 invitation_id uuid NOT NULL, workspace_child_id uuid NOT NULL, workspace_id uuid NOT NULL,
 capabilities jsonb NOT NULL CHECK(jsonb_typeof(capabilities)='array'), PRIMARY KEY(invitation_id,workspace_child_id),
 FOREIGN KEY(invitation_id,workspace_id) REFERENCES invitations(id,workspace_id),
 FOREIGN KEY(workspace_child_id,workspace_id) REFERENCES workspace_children(id,workspace_id)
);
CREATE TABLE audit_logs (
 id uuid PRIMARY KEY, workspace_id uuid REFERENCES workspaces(id), actor_user_id uuid REFERENCES users(id),
 action text NOT NULL, object_type text NOT NULL, object_id uuid, metadata jsonb NOT NULL DEFAULT '{}',
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_workspace ON audit_logs(workspace_id,created_at DESC);
CREATE TABLE idempotency_records (
 membership_id uuid NOT NULL REFERENCES memberships(id), request_key text NOT NULL, operation text NOT NULL,
 request_hash text NOT NULL, response jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(membership_id,request_key)
);
