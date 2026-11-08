-- Decision 133: a well-formed typed 4xx on the cancellation POST stops automatic POSTs with PG_REQUEST_REJECTED and keeps
-- the response status and error type. Existing FORBIDDEN/INVALID holds and their resume history are not touched: the new
-- columns stay NULL for them, and no historical generic error is reinterpreted as a rejection.
ALTER TABLE refund_cancellation_hold
    ADD COLUMN rejection_status INT NULL,
    ADD COLUMN rejection_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    DROP CHECK ck_cancel_hold_observation,
    ADD CONSTRAINT ck_cancel_hold_observation CHECK (
        observed_at >= claimed_at AND reason IN ('PG_REQUEST_FORBIDDEN', 'PG_REQUEST_INVALID', 'PG_REQUEST_REJECTED')),
    ADD CONSTRAINT ck_cancel_hold_rejection CHECK (
        (reason = 'PG_REQUEST_REJECTED' AND rejection_status BETWEEN 400 AND 499
            AND rejection_status NOT IN (401, 403, 404, 408, 429) AND rejection_type IS NOT NULL)
        OR (reason <> 'PG_REQUEST_REJECTED' AND rejection_status IS NULL AND rejection_type IS NULL));
