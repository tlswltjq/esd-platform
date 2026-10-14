-- Project ownership follows Auth's JWT subject directly.
-- Historical tables remain as migration history; runtime has no workspace or seller mapping.
UPDATE project_review r JOIN studio_project p ON p.id=r.project_id
SET r.snapshot=JSON_SET(JSON_REMOVE(r.snapshot,'$.sellerId'),'$.ownerSubject',p.owner_subject);

ALTER TABLE studio_project DROP COLUMN seller_id;
