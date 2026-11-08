-- Decision 114: the append-only approval investigation record is audit evidence.
-- V34 created its foreign key with ON DELETE CASCADE, so deleting an approval (or its purchase,
-- which cascades into payment_approval) silently erased the investigation history.
-- Re-create the key with the default RESTRICT, matching approval_closure (V37). Rows are not rewritten.
ALTER TABLE approval_investigation DROP FOREIGN KEY fk_approval_investigation_attempt;
ALTER TABLE approval_investigation
    ADD CONSTRAINT fk_approval_investigation_attempt FOREIGN KEY (approval_id) REFERENCES payment_approval(approval_id);
