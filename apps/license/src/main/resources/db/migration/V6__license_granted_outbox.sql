-- Preserve unsupported legacy payloads before enabling the current producer.
CREATE TABLE license_legacy_outbox_archive LIKE outbox_event;

INSERT INTO license_legacy_outbox_archive
SELECT * FROM outbox_event
WHERE status = 'PENDING'
  AND event_type IN ('LicenseIssued', 'LicenseRevoked', 'LicenseIssueFailed');

UPDATE outbox_event
SET status = 'DEAD', next_attempt_at = NULL,
    last_error = 'Legacy contract quarantined before enabling LicenseGranted relay'
WHERE status = 'PENDING'
  AND event_type IN ('LicenseIssued', 'LicenseRevoked', 'LicenseIssueFailed');
