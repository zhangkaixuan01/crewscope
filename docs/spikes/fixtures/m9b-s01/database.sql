-- Disposable prototype schema ONLY. This is not a Flyway migration or the product schema.
CREATE SCHEMA spike;
SET search_path = spike;
-- Simplified V20-shaped provider references: demonstrate why overwriting a hash is invalid.
CREATE TABLE provider (key text PRIMARY KEY, hash text NOT NULL, definition jsonb NOT NULL,
  status text NOT NULL, version bigint NOT NULL, UNIQUE(key,hash));
CREATE TABLE catalog (id int PRIMARY KEY, provider_key text NOT NULL, hash text NOT NULL,
  CONSTRAINT catalog_provider_fk FOREIGN KEY(provider_key,hash) REFERENCES provider(key,hash));
CREATE TABLE connection (id int PRIMARY KEY, provider_key text NOT NULL, hash text NOT NULL,
  CONSTRAINT connection_provider_fk FOREIGN KEY(provider_key,hash) REFERENCES provider(key,hash));
INSERT INTO provider VALUES('sample','old','{"endpoint":"https://old.example"}','ACTIVE',0);
INSERT INTO catalog VALUES(1,'sample','old');
INSERT INTO connection VALUES(1,'sample','old');
DO $$ BEGIN
  BEGIN UPDATE provider SET hash='new' WHERE key='sample';
    RAISE EXCEPTION 'unexpected_in_place_hash_update' USING ERRCODE='ZX001';
  EXCEPTION WHEN foreign_key_violation THEN NULL; END;
END $$;
CREATE TABLE provider_revision (key text NOT NULL, hash text NOT NULL, definition jsonb NOT NULL,
  PRIMARY KEY(key,hash), UNIQUE(key,hash,definition),
  FOREIGN KEY(key) REFERENCES provider(key) DEFERRABLE INITIALLY DEFERRED);
INSERT INTO provider_revision SELECT key,hash,definition FROM provider;
ALTER TABLE catalog DROP CONSTRAINT catalog_provider_fk;
ALTER TABLE catalog ADD CONSTRAINT catalog_provider_fk FOREIGN KEY(provider_key,hash) REFERENCES provider_revision(key,hash);
ALTER TABLE connection DROP CONSTRAINT connection_provider_fk;
ALTER TABLE connection ADD CONSTRAINT connection_provider_fk FOREIGN KEY(provider_key,hash) REFERENCES provider_revision(key,hash);
-- Including content here proves root projection consistency in this small fixture; production
-- may use a deferred constraint trigger for full definition fields instead of a wide JSON index.
ALTER TABLE provider ADD CONSTRAINT current_revision_fk FOREIGN KEY(key,hash,definition)
  REFERENCES provider_revision(key,hash,definition) DEFERRABLE INITIALLY DEFERRED;
CREATE FUNCTION guard_provider_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'immutable provider revision'; END $$;
CREATE TRIGGER provider_revision_guard BEFORE UPDATE OR DELETE ON provider_revision
  FOR EACH ROW EXECUTE FUNCTION guard_provider_revision();
BEGIN;
SELECT key FROM provider WHERE key='sample' FOR UPDATE;
INSERT INTO provider_revision VALUES('sample','new','{"endpoint":"https://new.example"}');
UPDATE provider SET hash='new',definition='{"endpoint":"https://new.example"}',version=version+1
  WHERE key='sample' AND version=0;
COMMIT;
BEGIN;
INSERT INTO provider VALUES('fresh','first','{"endpoint":"https://fresh.example"}','ACTIVE',0);
INSERT INTO provider_revision SELECT key,hash,definition FROM provider WHERE key='fresh';
COMMIT;
DO $$ BEGIN
  IF (SELECT r.definition->>'endpoint' FROM connection c JOIN provider_revision r
      ON (c.provider_key,c.hash)=(r.key,r.hash) WHERE c.id=1) <> 'https://old.example'
    OR (SELECT hash FROM catalog WHERE id=1) <> 'old'
    OR (SELECT hash FROM provider WHERE key='sample') <> 'new'
    THEN RAISE EXCEPTION 'provider history or current pointer changed incorrectly'; END IF;
  BEGIN UPDATE provider_revision SET definition='{}' WHERE key='sample';
    RAISE EXCEPTION 'unexpected_revision_update' USING ERRCODE='ZX001';
  EXCEPTION WHEN SQLSTATE 'P0001' THEN NULL; END;
  BEGIN DELETE FROM provider_revision WHERE key='sample';
    RAISE EXCEPTION 'unexpected_revision_delete' USING ERRCODE='ZX001';
  EXCEPTION WHEN SQLSTATE 'P0001' THEN NULL; END;
  BEGIN INSERT INTO connection VALUES(2,'sample','missing');
    RAISE EXCEPTION 'unexpected_missing_revision' USING ERRCODE='ZX001';
  EXCEPTION WHEN foreign_key_violation THEN NULL; END;
END $$;
UPDATE provider SET status='DISABLED',version=version+1 WHERE key='sample';
DO $$ BEGIN
  IF EXISTS(SELECT 1 FROM connection c JOIN provider p ON p.key=c.provider_key
    JOIN provider_revision r ON (r.key,r.hash)=(c.provider_key,c.hash) WHERE c.id=1 AND p.status='ACTIVE')
    THEN RAISE EXCEPTION 'old revision bypasses disabled root'; END IF;
END $$;
CREATE TABLE team (id int PRIMARY KEY, epoch bigint NOT NULL DEFAULT 1);
CREATE TABLE member (id int PRIMARY KEY, team_id int NOT NULL REFERENCES team, active boolean NOT NULL, owner boolean NOT NULL);
INSERT INTO team VALUES (1, 1), (2, 1);
INSERT INTO member VALUES (1, 1, true, true), (2, 1, true, true), (3, 2, true, true);
CREATE TABLE disposition (id int PRIMARY KEY, member_id int NOT NULL, status text NOT NULL,
  version bigint NOT NULL CHECK(version > 0), updated_at timestamptz NOT NULL,
  CONSTRAINT old_status CHECK(status IN ('READ','ACTED','ARCHIVED')));
INSERT INTO disposition VALUES
  (1,1,'READ',1,'2026-09-22Z'), (2,1,'ACTED',2,'2026-09-22Z'), (3,1,'ARCHIVED',3,'2026-09-22Z');
ALTER TABLE disposition DROP CONSTRAINT old_status;
ALTER TABLE disposition ADD CONSTRAINT new_status CHECK(status IN ('UNREAD','READ','ACTED','ARCHIVED'));
CREATE FUNCTION guard_disposition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'no deletion'; END IF;
  IF NEW.id <> OLD.id OR NEW.member_id <> OLD.member_id OR NEW.version <> OLD.version + 1
    OR NEW.updated_at < OLD.updated_at THEN RAISE EXCEPTION 'immutable facts/version'; END IF;
  IF NOT ((OLD.status, NEW.status) IN (('UNREAD','READ'),('UNREAD','ACTED'),('UNREAD','ARCHIVED'),
    ('READ','UNREAD'),('READ','ACTED'),('READ','ARCHIVED'),('ACTED','UNREAD'),('ACTED','ARCHIVED'),('ARCHIVED','READ')))
    THEN RAISE EXCEPTION 'invalid transition'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER disposition_guard BEFORE UPDATE OR DELETE ON disposition FOR EACH ROW EXECUTE FUNCTION guard_disposition();
INSERT INTO disposition VALUES (5,1,'ARCHIVED',1,'2026-09-22Z');
DO $$ BEGIN
  BEGIN UPDATE disposition SET status='UNREAD',version=2 WHERE id=5;
    RAISE EXCEPTION 'unexpected_restore_to_unread' USING ERRCODE='ZX001';
  EXCEPTION WHEN SQLSTATE 'P0001' THEN NULL; END;
END $$;
UPDATE disposition SET status='READ',version=4 WHERE id=3 AND version=3;
UPDATE disposition SET status='UNREAD',version=5 WHERE id=3 AND version=4;
DO $$
DECLARE affected int;
BEGIN
  UPDATE disposition SET status='ACTED',version=4 WHERE id=3 AND version=3;
  GET DIAGNOSTICS affected = ROW_COUNT;
  IF affected <> 0 THEN RAISE EXCEPTION 'stale version overwritten'; END IF;
  BEGIN DELETE FROM disposition WHERE id=3; RAISE EXCEPTION 'unexpected_delete' USING ERRCODE='ZX001';
  EXCEPTION WHEN SQLSTATE 'P0001' THEN NULL; END;
  IF (SELECT count(*) FROM (VALUES(1),(2),(3),(4)) i(id)
      LEFT JOIN disposition d USING(id) WHERE COALESCE(d.status,'UNREAD')='UNREAD') <> 2
    THEN RAISE EXCEPTION 'initial and explicit unread count mismatch'; END IF;
END $$;
CREATE TABLE work (id bigint PRIMARY KEY, org int NOT NULL, team_id int NOT NULL,
  updated_at timestamptz NOT NULL, priority int NOT NULL, deadline timestamptz);
INSERT INTO work SELECT i, 1, 1, '2026-09-22Z'::timestamptz + (i/3)*interval '1 second',
  i%4, CASE WHEN i%3=0 THEN NULL ELSE '2026-09-23Z'::timestamptz END FROM generate_series(1,10000) i;
INSERT INTO work SELECT i, 2, 2, '2026-09-24Z', 0, NULL FROM generate_series(10001,10200) i;
CREATE INDEX work_scope_page ON work(org,team_id,updated_at DESC,id DESC);
ANALYZE work;
CREATE TABLE transfer_item(job int, assignment int, old_version int, state text, PRIMARY KEY(job,assignment));
INSERT INTO transfer_item VALUES(1,11,1,'PENDING'),(1,12,1,'PENDING');
CREATE TABLE assignment(id int PRIMARY KEY, owner_id int, version int);
INSERT INTO assignment VALUES(11,1,1),(12,1,1);
-- Commit one item and checkpoint in one transaction; subsequent sessions resume the other item.
BEGIN;
UPDATE assignment SET owner_id=2,version=2 WHERE id=11 AND owner_id=1 AND version=1;
UPDATE transfer_item SET state='DONE' WHERE job=1 AND assignment=11;
COMMIT;
