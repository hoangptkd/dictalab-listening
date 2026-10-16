-- Expand only. Historical payment and entitlement projections remain intact.
ALTER TABLE payment_orders ALTER COLUMN amount_vnd TYPE BIGINT;
ALTER TABLE payment_orders ADD COLUMN product_code VARCHAR(32) NOT NULL DEFAULT 'PREMIUM_30D';
ALTER TABLE payment_orders ADD COLUMN price_version VARCHAR(32) NOT NULL DEFAULT '2026-10-v1';
ALTER TABLE payment_orders ADD COLUMN policy_version VARCHAR(32) NOT NULL DEFAULT 'MANUAL_REVIEW_V1';
ALTER TABLE payment_orders ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency='VND');
ALTER TABLE payment_orders ADD COLUMN edit_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment_orders ADD COLUMN next_check_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE payment_orders ADD COLUMN check_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payment_orders ADD COLUMN check_lease VARCHAR(36);
ALTER TABLE payment_orders ADD COLUMN check_until TIMESTAMP WITH TIME ZONE;
CREATE INDEX payment_orders_check_due ON payment_orders(next_check_at,order_code);
CREATE TABLE payment_mutex (id INTEGER PRIMARY KEY);
INSERT INTO payment_mutex(id) VALUES(1);

CREATE TABLE payment_events (
 id VARCHAR(36) PRIMARY KEY, event_key VARCHAR(180) NOT NULL UNIQUE,
 order_code BIGINT, source VARCHAR(24) NOT NULL, kind VARCHAR(48) NOT NULL,
 payload_hash VARCHAR(64) NOT NULL, payload TEXT NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE, recorded_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX payment_events_order ON payment_events(order_code,recorded_at,id);
CREATE TABLE payment_deliveries (
 id VARCHAR(36) PRIMARY KEY, event_id VARCHAR(36) NOT NULL REFERENCES payment_events(id),
 received_at TIMESTAMP WITH TIME ZONE NOT NULL, duplicate BOOLEAN NOT NULL
);
CREATE TABLE payment_inbox (
 event_id VARCHAR(36) PRIMARY KEY REFERENCES payment_events(id),
 status VARCHAR(20) NOT NULL CHECK(status IN ('RECEIVED','PROCESSING','PROCESSED','RETRY','QUARANTINED')),
 attempts INTEGER NOT NULL DEFAULT 0, next_attempt TIMESTAMP WITH TIME ZONE NOT NULL,
 lease_id VARCHAR(36), locked_until TIMESTAMP WITH TIME ZONE, last_error VARCHAR(48)
);
CREATE INDEX payment_inbox_due ON payment_inbox(status,next_attempt);
CREATE TABLE payment_receipts (
 id VARCHAR(36) PRIMARY KEY, provider VARCHAR(16) NOT NULL DEFAULT 'PAYOS', channel VARCHAR(64) NOT NULL,
 reference VARCHAR(150) NOT NULL, order_code BIGINT, payment_link_id VARCHAR(64) NOT NULL,
 amount_vnd BIGINT NOT NULL CHECK(amount_vnd>0 AND amount_vnd<=1000000000000), currency VARCHAR(3) NOT NULL CHECK(currency='VND'),
 content_hash VARCHAR(64) NOT NULL, event_id VARCHAR(36) NOT NULL REFERENCES payment_events(id),
 effective_at TIMESTAMP WITH TIME ZONE NOT NULL, recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
 bank_verified BOOLEAN NOT NULL DEFAULT FALSE, UNIQUE(provider,channel,reference)
);
CREATE INDEX payment_receipts_order ON payment_receipts(order_code,recorded_at);
CREATE TABLE payment_receipt_sources (
 receipt_id VARCHAR(36) PRIMARY KEY REFERENCES payment_receipts(id), payer_fingerprint VARCHAR(64) NOT NULL,
 event_id VARCHAR(36) NOT NULL REFERENCES payment_events(id), created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE payment_allocations (
 receipt_id VARCHAR(36) PRIMARY KEY REFERENCES payment_receipts(id),
 order_code BIGINT NOT NULL REFERENCES payment_orders(order_code),
 amount_vnd BIGINT NOT NULL CHECK(amount_vnd>0), created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE payment_idempotency (
 actor_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE, operation VARCHAR(48) NOT NULL,
 request_key VARCHAR(100) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 resource_id VARCHAR(100), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(actor_id,operation,request_key)
);
CREATE TABLE premium_grants (
 id VARCHAR(36) PRIMARY KEY, order_code BIGINT NOT NULL UNIQUE REFERENCES payment_orders(order_code),
 user_id BIGINT REFERENCES app_users(id) ON DELETE SET NULL,
 source VARCHAR(32) NOT NULL, starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
 ends_at TIMESTAMP WITH TIME ZONE NOT NULL, duration_seconds BIGINT NOT NULL CHECK(duration_seconds>0),
 revoked_seconds BIGINT NOT NULL DEFAULT 0 CHECK(revoked_seconds>=0 AND revoked_seconds<=duration_seconds),
 amount_vnd BIGINT NOT NULL CHECK(amount_vnd>0), recognized_vnd BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX premium_grants_user ON premium_grants(user_id,starts_at,id);
CREATE TABLE premium_adjustments (
 id VARCHAR(36) PRIMARY KEY, grant_id VARCHAR(36) NOT NULL REFERENCES premium_grants(id),
 refund_id VARCHAR(36) NOT NULL UNIQUE, seconds_removed BIGINT NOT NULL CHECK(seconds_removed>=0),
 reason VARCHAR(1000) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE ledger_accounts (code VARCHAR(32) PRIMARY KEY, label VARCHAR(100) NOT NULL);
INSERT INTO ledger_accounts(code,label) VALUES
 ('COLLECTION_CLEARING','Provider receipts awaiting bank evidence'),('BANK_SCOPED','Identified DictaLab bank transactions'),
 ('DEFERRED_SERVICE','Premium service obligation'),('SERVICE_REVENUE','Service revenue allocation'),
 ('UNALLOCATED','Unallocated customer funds'),('REFUND_PAYABLE','Approved refunds payable'),('REVENUE_REVERSAL','Service revenue reversals');
CREATE TABLE ledger_journals (
 id VARCHAR(36) PRIMARY KEY, event_key VARCHAR(180) NOT NULL UNIQUE,
 order_code BIGINT, kind VARCHAR(40) NOT NULL, currency VARCHAR(3) NOT NULL CHECK(currency='VND'),
 status VARCHAR(8) NOT NULL CHECK(status IN ('DRAFT','POSTED')),
 effective_at TIMESTAMP WITH TIME ZONE NOT NULL, recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
 reversal_of VARCHAR(36) UNIQUE REFERENCES ledger_journals(id)
);
CREATE TABLE ledger_entries (
 id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, journal_id VARCHAR(36) NOT NULL REFERENCES ledger_journals(id),
 account_code VARCHAR(32) NOT NULL REFERENCES ledger_accounts(code),
 debit_vnd BIGINT NOT NULL DEFAULT 0, credit_vnd BIGINT NOT NULL DEFAULT 0,
 CHECK ((debit_vnd>0 AND credit_vnd=0) OR (credit_vnd>0 AND debit_vnd=0))
);
CREATE INDEX ledger_entries_journal ON ledger_entries(journal_id);
CREATE TABLE reconciliation_runs (
 id VARCHAR(36) PRIMARY KEY, order_code BIGINT NOT NULL, source VARCHAR(24) NOT NULL,
 status VARCHAR(16) NOT NULL, result_code VARCHAR(48), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 completed_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE reconciliation_cases (
 id VARCHAR(36) PRIMARY KEY, case_key VARCHAR(180) NOT NULL UNIQUE, order_code BIGINT,
 kind VARCHAR(48) NOT NULL, status VARCHAR(24) NOT NULL CHECK(status IN ('OPEN','INVESTIGATING','RESOLVED','ACCEPTED_EXCEPTION')),
 owner_id BIGINT REFERENCES app_users(id) ON DELETE SET NULL, version BIGINT NOT NULL DEFAULT 0,
 evidence VARCHAR(1000) NOT NULL, resolution VARCHAR(1000),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, due_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX reconciliation_cases_status_due ON reconciliation_cases(status,due_at);
CREATE TABLE payment_permissions (
 user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 permission VARCHAR(32) NOT NULL CHECK(permission IN ('PAYMENT_VIEW','PAYMENT_RECONCILE','REFUND_REQUEST','REFUND_APPROVE','REFUND_EXECUTE','PAYMENT_AUDIT','PAYMENT_MANAGE')),
 PRIMARY KEY(user_id,permission)
);
CREATE TABLE payment_bootstrap (id INTEGER PRIMARY KEY, created_at TIMESTAMP WITH TIME ZONE NOT NULL);
CREATE TABLE payment_audit (
 id VARCHAR(36) PRIMARY KEY, actor_id BIGINT, action VARCHAR(48) NOT NULL,
 resource_id VARCHAR(100) NOT NULL, reason VARCHAR(1000) NOT NULL, correlation_id VARCHAR(36) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX payment_audit_created ON payment_audit(created_at,id);
CREATE TABLE payment_totp (
 user_id BIGINT PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE,
 secret_cipher TEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 last_step BIGINT NOT NULL DEFAULT -1, attempts INTEGER NOT NULL DEFAULT 0,
 blocked_until TIMESTAMP WITH TIME ZONE, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE payment_challenges (
 id VARCHAR(36) PRIMARY KEY, actor_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 binding_hash VARCHAR(64) NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL, used_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE statement_imports (
 id VARCHAR(36) PRIMARY KEY, file_hash VARCHAR(64) NOT NULL UNIQUE, mapping_version VARCHAR(24) NOT NULL,
 source VARCHAR(80) NOT NULL, status VARCHAR(16) NOT NULL CHECK(status IN ('PREVIEW','CONFIRMED')),
 actor_id BIGINT NOT NULL, line_count INTEGER NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 confirmed_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE statement_lines (
 id VARCHAR(36) PRIMARY KEY, import_id VARCHAR(36) NOT NULL REFERENCES statement_imports(id),
 line_key VARCHAR(64) NOT NULL, reference VARCHAR(150) NOT NULL, amount_vnd BIGINT NOT NULL CHECK(amount_vnd>0),
 direction VARCHAR(3) NOT NULL CHECK(direction IN ('IN','OUT')), business_date DATE NOT NULL,
 scope VARCHAR(16) NOT NULL CHECK(scope IN ('DICTALAB','PERSONAL')), matched_receipt VARCHAR(36) REFERENCES payment_receipts(id),
 UNIQUE(import_id,line_key)
);
CREATE TABLE bank_evidence (
 line_key VARCHAR(64) PRIMARY KEY, reference VARCHAR(150) NOT NULL, amount_vnd BIGINT NOT NULL,
 direction VARCHAR(3) NOT NULL, business_date DATE NOT NULL,
 statement_line_id VARCHAR(36) NOT NULL REFERENCES statement_lines(id), created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE refund_requests (
 id VARCHAR(36) PRIMARY KEY, order_code BIGINT NOT NULL REFERENCES payment_orders(order_code),
 requested_by BIGINT NOT NULL,
 amount_vnd BIGINT NOT NULL CHECK(amount_vnd>0), seconds_to_revoke BIGINT NOT NULL CHECK(seconds_to_revoke>=0),
 reason VARCHAR(1000) NOT NULL, recipient_hash VARCHAR(64) NOT NULL, recipient_mask VARCHAR(80) NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('REQUESTED','APPROVED','REJECTED','EXECUTING','UNKNOWN','SUCCEEDED','FAILED','CANCELLED')),
 policy_version VARCHAR(32) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 approved_by BIGINT, approved_at TIMESTAMP WITH TIME ZONE, approval_hash VARCHAR(64), approval_expires_at TIMESTAMP WITH TIME ZONE,
 evidence_line_key VARCHAR(64) UNIQUE REFERENCES bank_evidence(line_key), executed_by BIGINT,
 ticket_id BIGINT REFERENCES support_tickets(id) ON DELETE SET NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX refund_requests_order ON refund_requests(order_code,status);
CREATE TABLE refund_attempts (
 id VARCHAR(36) PRIMARY KEY, refund_id VARCHAR(36) NOT NULL REFERENCES refund_requests(id),
 attempt_key VARCHAR(100) NOT NULL UNIQUE, result VARCHAR(16) NOT NULL,
 evidence VARCHAR(500) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE payment_outbox (
 id VARCHAR(36) PRIMARY KEY, event_key VARCHAR(180) NOT NULL UNIQUE,
 user_id BIGINT REFERENCES app_users(id) ON DELETE SET NULL, order_code BIGINT NOT NULL,
 kind VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INTEGER NOT NULL DEFAULT 0,
 next_attempt TIMESTAMP WITH TIME ZONE NOT NULL, lease_id VARCHAR(36), locked_until TIMESTAMP WITH TIME ZONE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, delivered_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE payment_daily_closes (
 id VARCHAR(36) PRIMARY KEY, business_date DATE NOT NULL, version INTEGER NOT NULL,
 cutoff TIMESTAMP WITH TIME ZONE NOT NULL, snapshot TEXT NOT NULL, digest VARCHAR(64) NOT NULL,
 actor_id BIGINT NOT NULL, reason VARCHAR(1000) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(business_date,version)
);
CREATE TABLE payment_close_reopens (
 id VARCHAR(36) PRIMARY KEY, close_id VARCHAR(36) NOT NULL REFERENCES payment_daily_closes(id),
 requested_by BIGINT NOT NULL, approved_by BIGINT, reason VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, approved_at TIMESTAMP WITH TIME ZONE
);
