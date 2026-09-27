-- F04 / contract §5.1: Inbox disposition becomes reversible.
--
-- A persisted UNREAD row is now legal (a member can un-mark a read or acted item without deleting
-- the authority row), so the V27 closed-value CHECK and the monotonic-rank guard are replaced.
-- Every non-monotonic protection of the V27 guard is kept verbatim: no DELETE, scope and creation
-- facts immutable, version must advance by exactly one, updated_at must not move backwards.

ALTER TABLE crewscope.inbox_disposition
    DROP CONSTRAINT ck_inbox_disposition_values_v27;

ALTER TABLE crewscope.inbox_disposition
    ADD CONSTRAINT ck_inbox_disposition_values_v48 CHECK (
        status IN ('UNREAD', 'READ', 'ACTED', 'ARCHIVED') AND version > 0
        AND updated_at >= created_at
    );

DROP TRIGGER trg_inbox_disposition_guard_v27 ON crewscope.inbox_disposition;

DROP FUNCTION crewscope.guard_inbox_disposition_v27();

CREATE FUNCTION crewscope.guard_inbox_disposition_v48()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'InboxDisposition cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.organization_id, NEW.team_id, NEW.member_id, NEW.inbox_item_id,
           NEW.created_at, NEW.created_by_principal_id)
       IS DISTINCT FROM
       ROW(OLD.organization_id, OLD.team_id, OLD.member_id, OLD.inbox_item_id,
           OLD.created_at, OLD.created_by_principal_id)
       OR NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'invalid InboxDisposition transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_inbox_disposition_guard_v48
    BEFORE UPDATE OR DELETE ON crewscope.inbox_disposition
    FOR EACH ROW EXECUTE FUNCTION crewscope.guard_inbox_disposition_v48();
