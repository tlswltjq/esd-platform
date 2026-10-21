-- Catalog V10 -> V11 cutover. Stop ALL old Catalog writers/relays before running.
-- Run against the Catalog database only. No historical payload is deleted.
CREATE TABLE IF NOT EXISTS catalog_legacy_outbox_archive LIKE outbox_event;
START TRANSACTION;
INSERT IGNORE INTO catalog_legacy_outbox_archive
SELECT * FROM outbox_event
WHERE status IN ('PENDING', 'DEAD')
  AND event_type NOT IN ('ProjectReleaseCompleted', 'ProjectReleaseRejected');
UPDATE outbox_event
SET status = 'DEAD', last_error = 'CATALOG_LEGACY_QUARANTINE: original preserved in catalog_legacy_outbox_archive'
WHERE status = 'PENDING'
  AND event_type NOT IN ('ProjectReleaseCompleted', 'ProjectReleaseRejected');
COMMIT;
-- Must be zero before enabling the new relay. Do not requeue quarantined legacy events.
SELECT COUNT(*) AS unexpected_legacy_pending FROM outbox_event
WHERE status = 'PENDING'
  AND event_type NOT IN ('ProjectReleaseCompleted', 'ProjectReleaseRejected');
