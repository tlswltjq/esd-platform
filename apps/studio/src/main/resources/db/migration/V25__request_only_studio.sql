-- Studio owns creator metadata and outgoing requests, not review decisions or publication.
RENAME TABLE project_review TO studio_review_request,
             project_publication TO studio_release_request;

ALTER TABLE studio_project
    DROP COLUMN status,
    DROP COLUMN published_publication_id,
    RENAME COLUMN current_review_id TO current_review_request_id,
    RENAME COLUMN current_publication_id TO current_release_request_id;

ALTER TABLE studio_review_request RENAME COLUMN decided_at TO result_received_at;

ALTER TABLE studio_release_request
    DROP INDEX uk_project_publication_version,
    DROP COLUMN product_id,
    RENAME COLUMN review_id TO review_request_id,
    RENAME COLUMN registered_at TO result_received_at,
    ADD KEY idx_studio_release_request(project_id,id);

UPDATE studio_release_request SET status='COMPLETED' WHERE status='REGISTERED';
UPDATE studio_release_request SET status='REJECTED' WHERE status='FAILED';
