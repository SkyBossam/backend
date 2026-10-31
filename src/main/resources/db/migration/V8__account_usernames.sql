ALTER TABLE users ADD COLUMN username text;
CREATE UNIQUE INDEX ux_users_username ON users(username);

-- Preserve account UUIDs, passwords, memberships, device bindings and care history.
-- Existing valid email prefixes become IDs; collisions receive a numeric suffix.
DO $$
DECLARE account record; base text; candidate text; suffix text; counter integer;
BEGIN
 FOR account IN SELECT id,email FROM users ORDER BY created_at,id LOOP
   base := lower(split_part(account.email,'@',1));
   IF base !~ '^[a-z0-9][a-z0-9._-]{0,31}$' THEN
     base := 'user_' || left(replace(account.id::text,'-',''),12);
   END IF;
   candidate := base; counter := 1;
   WHILE EXISTS(SELECT 1 FROM users WHERE username=candidate) LOOP
     counter := counter+1; suffix := '_' || counter::text;
     candidate := left(base,32-length(suffix)) || suffix;
   END LOOP;
   UPDATE users SET username=candidate WHERE id=account.id;
 END LOOP;
END $$;

ALTER TABLE users ALTER COLUMN username SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT ck_users_username CHECK(username ~ '^[a-z0-9][a-z0-9._-]{0,31}$');

-- New accounts use a username and do not need an email address.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
