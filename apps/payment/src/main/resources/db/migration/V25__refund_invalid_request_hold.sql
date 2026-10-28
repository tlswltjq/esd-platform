ALTER TABLE refund_cancellation_hold
    DROP CHECK ck_cancel_hold_observation,
    ADD CONSTRAINT ck_cancel_hold_observation CHECK (
        observed_at >= claimed_at AND reason IN ('PG_REQUEST_FORBIDDEN', 'PG_REQUEST_INVALID'));

-- Keep existing observations and resume history intact. Unknown HTTP errors do not create holds.
