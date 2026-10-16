-- Database guards apply even when SQL is issued outside the application service.
CREATE FUNCTION payment_guard_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE d NUMERIC; c NUMERIC; n BIGINT;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Journal history cannot be deleted'; END IF;
 IF TG_OP='UPDATE' AND OLD.status='POSTED' THEN RAISE EXCEPTION 'Posted journal is immutable'; END IF;
 IF NEW.status='POSTED' THEN
   SELECT COALESCE(sum(debit_vnd),0),COALESCE(sum(credit_vnd),0),count(*) INTO d,c,n FROM ledger_entries WHERE journal_id=NEW.id;
   IF n<2 OR d<>c OR d<=0 THEN RAISE EXCEPTION 'Journal must balance before posting'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER payment_journal_guard BEFORE INSERT OR UPDATE OR DELETE ON ledger_journals FOR EACH ROW EXECUTE FUNCTION payment_guard_journal();
CREATE FUNCTION payment_guard_entry() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE j VARCHAR(36); s VARCHAR(8);
BEGIN
 j:=CASE WHEN TG_OP='DELETE' THEN OLD.journal_id ELSE NEW.journal_id END;
 SELECT status INTO s FROM ledger_journals WHERE id=j FOR UPDATE;
 IF s='POSTED' THEN RAISE EXCEPTION 'Posted entries are immutable'; END IF;
 IF TG_OP='UPDATE' AND OLD.journal_id<>NEW.journal_id THEN RAISE EXCEPTION 'Cannot move entries'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER payment_entry_guard BEFORE INSERT OR UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION payment_guard_entry();
CREATE FUNCTION payment_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Payment evidence is append only'; END $$;
CREATE TRIGGER payment_events_immutable BEFORE UPDATE OR DELETE ON payment_events FOR EACH ROW EXECUTE FUNCTION payment_append_only();
CREATE TRIGGER payment_audit_immutable BEFORE UPDATE OR DELETE ON payment_audit FOR EACH ROW EXECUTE FUNCTION payment_append_only();
CREATE TRIGGER payment_closes_immutable BEFORE UPDATE OR DELETE ON payment_daily_closes FOR EACH ROW EXECUTE FUNCTION payment_append_only();
CREATE FUNCTION payment_guard_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Receipt evidence cannot be deleted'; END IF;
 IF (to_jsonb(NEW)-'bank_verified') IS DISTINCT FROM (to_jsonb(OLD)-'bank_verified') OR (OLD.bank_verified AND NOT NEW.bank_verified)
 THEN RAISE EXCEPTION 'Receipt evidence is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER payment_receipts_immutable BEFORE UPDATE OR DELETE ON payment_receipts FOR EACH ROW EXECUTE FUNCTION payment_guard_receipt();
CREATE TRIGGER payment_sources_immutable BEFORE UPDATE OR DELETE ON payment_receipt_sources FOR EACH ROW EXECUTE FUNCTION payment_append_only();
CREATE TRIGGER payment_allocations_immutable BEFORE UPDATE OR DELETE ON payment_allocations FOR EACH ROW EXECUTE FUNCTION payment_append_only();
CREATE TRIGGER payment_adjustments_immutable BEFORE UPDATE OR DELETE ON premium_adjustments FOR EACH ROW EXECUTE FUNCTION payment_append_only();
CREATE TRIGGER payment_attempts_immutable BEFORE UPDATE OR DELETE ON refund_attempts FOR EACH ROW EXECUTE FUNCTION payment_append_only();
CREATE TRIGGER payment_bank_evidence_immutable BEFORE UPDATE OR DELETE ON bank_evidence FOR EACH ROW EXECUTE FUNCTION payment_append_only();
-- Optional production role is provisioned separately; CI migrates with its disposable owner.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dictalab_runtime') THEN
  REVOKE UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES ON payment_events,payment_audit,payment_daily_closes,payment_receipt_sources,payment_allocations,premium_adjustments,refund_attempts,bank_evidence FROM dictalab_runtime;
  REVOKE DELETE,TRUNCATE,TRIGGER,REFERENCES ON ledger_journals,ledger_entries,payment_receipts FROM dictalab_runtime;
  REVOKE UPDATE ON ledger_journals,payment_receipts FROM dictalab_runtime;
  GRANT UPDATE(status) ON ledger_journals TO dictalab_runtime;
  GRANT UPDATE(bank_verified) ON payment_receipts TO dictalab_runtime;
 END IF;
END $$;
