-- Decision 133: a well-formed typed 4xx on the cancellation POST stops automatic POSTs with PG_REQUEST_REJECTED and keeps
-- the response status and error type. Decision 133 sets no format or length on the type, so it is stored whole.
-- Existing FORBIDDEN/INVALID holds and their resume history are not touched: the new columns stay NULL for them, and no
-- historical generic error is reinterpreted as a rejection. Every condition names IS NOT NULL / IS NULL explicitly so a
-- NULL cannot turn the CHECK into UNKNOWN (which MySQL accepts).
ALTER TABLE refund_cancellation_hold
    ADD COLUMN rejection_status INT NULL,
    ADD COLUMN rejection_type MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    DROP CHECK ck_cancel_hold_observation,
    ADD CONSTRAINT ck_cancel_hold_observation CHECK (
        observed_at >= claimed_at AND reason IN ('PG_REQUEST_FORBIDDEN', 'PG_REQUEST_INVALID', 'PG_REQUEST_REJECTED')),
    ADD CONSTRAINT ck_cancel_hold_rejection CHECK (
        (reason = 'PG_REQUEST_REJECTED'
            AND rejection_status IS NOT NULL AND rejection_status BETWEEN 400 AND 499
            AND rejection_status NOT IN (401, 403, 404, 408, 429)
            AND rejection_type IS NOT NULL AND CHAR_LENGTH(TRIM(rejection_type)) > 0)
        OR (reason <> 'PG_REQUEST_REJECTED' AND rejection_status IS NULL AND rejection_type IS NULL));
