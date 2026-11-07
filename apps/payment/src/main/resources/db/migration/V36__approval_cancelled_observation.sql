-- Decision 115: the approval observation also knows a verified full PG cancellation.
-- Only the allowed value set widens; every existing row already satisfies the new constraint, so no row is rewritten.
ALTER TABLE payment_approval
    DROP CHECK chk_approval_pg_state,
    ADD CONSTRAINT chk_approval_pg_state CHECK (latest_pg_state IS NULL OR latest_pg_state IN ('READY','PENDING','PAID','FAILED','CANCELLED'));
