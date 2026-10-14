CREATE TABLE studio_project (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, product_code VARCHAR(50) NOT NULL,
 owner_subject VARCHAR(100) NOT NULL, seller_id BIGINT NOT NULL,
 title VARCHAR(200) NOT NULL, short_description VARCHAR(500) NOT NULL DEFAULT '',
 thumbnail_url VARCHAR(1000), video_url VARCHAR(1000), content LONGTEXT NOT NULL,
 price BIGINT NOT NULL DEFAULT 0, current_build_id BIGINT, content_version BIGINT NOT NULL,
 status VARCHAR(30) NOT NULL, current_review_id BIGINT, current_publication_id BIGINT,
 published_publication_id BIGINT, entity_version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_studio_project_code(product_code), KEY idx_studio_owner(owner_subject,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE studio_build (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL,
 version VARCHAR(100) NOT NULL, file_name VARCHAR(255) NOT NULL, storage_path VARCHAR(1000) NOT NULL,
 file_size BIGINT NOT NULL, checksum VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_studio_build_version(project_id,version),
 CONSTRAINT fk_studio_build_project FOREIGN KEY(project_id) REFERENCES studio_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE project_review (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL,
 content_version BIGINT NOT NULL, build_id BIGINT NOT NULL, snapshot LONGTEXT NOT NULL,
 status VARCHAR(20) NOT NULL, feedback VARCHAR(2000), actor VARCHAR(100), decided_at DATETIME(6),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 KEY idx_project_review(project_id,id),
 CONSTRAINT fk_project_review_project FOREIGN KEY(project_id) REFERENCES studio_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE project_publication (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL,
 review_id BIGINT NOT NULL, content_version BIGINT NOT NULL, status VARCHAR(20) NOT NULL,
 product_id BIGINT, failure_reason VARCHAR(2000), registered_at DATETIME(6),
 request_event_id VARCHAR(64),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_project_publication_version(project_id,content_version),
 CONSTRAINT fk_publication_review FOREIGN KEY(review_id) REFERENCES project_review(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve ownership and project identities. Old reviews do not approve a new draft.
INSERT INTO studio_project(id,product_code,owner_subject,seller_id,title,content,price,content_version,status,created_at,updated_at)
SELECT g.id,g.product_code,w.owner_subject,g.workspace_id,g.title,'',CASE WHEN g.currency='KRW' THEN g.price ELSE 0 END,1,'DRAFT',g.created_at,g.updated_at
FROM game_project g JOIN workspace w ON w.id=g.workspace_id;

-- Only successful full artifacts enter the new build collection; old data remains available for audit.
INSERT INTO studio_build(id,project_id,version,file_name,storage_path,file_size,checksum,created_at,updated_at)
SELECT b.id,b.game_id,CONCAT(b.version,'-legacy-',b.id),'build.zip',b.storage_path,
       b.file_size,b.actual_checksum,b.created_at,b.updated_at
FROM game_build b WHERE b.status='VALIDATED' AND b.delta_from_version IS NULL
AND b.file_size>0 AND b.actual_checksum REGEXP '^[0-9a-f]{64}$';

-- Existing completed LIVE publications carry their original IDs and approved content.
INSERT INTO project_review(id,project_id,content_version,build_id,snapshot,status,actor,decided_at,created_at,updated_at)
SELECT r.id,r.game_id,r.id,r.build_id,
 JSON_OBJECT('projectId',g.id,'productCode',g.product_code,'sellerId',g.workspace_id,'contentVersion',r.id,
 'title',m.title,'shortDescription',m.short_description,'thumbnailUrl',COALESCE(m.cover_url,m.icon_url),
 'videoUrl',JSON_UNQUOTE(JSON_EXTRACT(m.trailers_json,'$[0]')),'content',m.detailed_description,
 'price',p.price,'currency',p.currency,'buildId',b.id,'version',b.version,
 'fileSize',b.file_size,'checksum',b.checksum,'storagePath',b.storage_path),
 'APPROVED','migration:published',r.published_at,r.created_at,r.updated_at
FROM game_release r JOIN game_project g ON g.id=r.game_id
JOIN submission s ON s.id=r.submission_id AND s.status='RELEASED'
JOIN studio_build b ON b.id=r.build_id
JOIN store_page_revision m ON m.id=r.metadata_revision_id AND m.status='PUBLISHED'
JOIN pricing_revision p ON p.id=r.pricing_revision_id
WHERE r.status='PUBLISHED' AND r.channel='LIVE';

INSERT INTO project_publication(id,project_id,review_id,content_version,status,registered_at,created_at,updated_at)
SELECT id,project_id,id,content_version,'REGISTERED',decided_at,created_at,updated_at FROM project_review;

UPDATE studio_project p JOIN
 (SELECT project_id,MAX(id) publication_id FROM project_publication GROUP BY project_id) latest ON latest.project_id=p.id
 JOIN project_review r ON r.id=latest.publication_id
 SET p.published_publication_id=latest.publication_id,p.current_publication_id=latest.publication_id,
 p.current_review_id=r.id,p.current_build_id=r.build_id,p.content_version=r.content_version+1,
 p.title=JSON_UNQUOTE(JSON_EXTRACT(r.snapshot,'$.title')),
 p.short_description=JSON_UNQUOTE(JSON_EXTRACT(r.snapshot,'$.shortDescription')),
 p.thumbnail_url=JSON_UNQUOTE(JSON_EXTRACT(r.snapshot,'$.thumbnailUrl')),
 p.content=JSON_UNQUOTE(JSON_EXTRACT(r.snapshot,'$.content')),
 p.price=CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(r.snapshot,'$.currency'))='KRW'
 THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(r.snapshot,'$.price')) AS UNSIGNED) ELSE 0 END;

-- Preserve editable legacy materials as a fresh draft without approving them.
UPDATE studio_project p JOIN (SELECT game_id,MAX(id) revision_id FROM store_page_revision GROUP BY game_id) latest
 ON latest.game_id=p.id JOIN store_page_revision m ON m.id=latest.revision_id
 SET p.title=m.title,p.short_description=m.short_description,p.content=m.detailed_description,
 p.thumbnail_url=COALESCE(m.cover_url,m.icon_url),
 p.video_url=JSON_UNQUOTE(JSON_EXTRACT(m.trailers_json,'$[0]'));
UPDATE studio_project p JOIN (SELECT game_id,MAX(id) revision_id FROM pricing_revision GROUP BY game_id) latest
 ON latest.game_id=p.id JOIN pricing_revision r ON r.id=latest.revision_id
 SET p.price=CASE WHEN r.currency='KRW' THEN r.price ELSE 0 END;
UPDATE studio_project p JOIN (SELECT project_id,MAX(id) build_id FROM studio_build GROUP BY project_id) latest
 ON latest.project_id=p.id SET p.current_build_id=latest.build_id;

-- Reserve the whole legacy ID range, including records intentionally not migrated.
SET @studio_next_build=(SELECT COALESCE(MAX(id),0)+1 FROM game_build);
SET @studio_build_sequence=CONCAT('ALTER TABLE studio_build AUTO_INCREMENT=',@studio_next_build);
PREPARE studio_sequence FROM @studio_build_sequence;
EXECUTE studio_sequence;
DEALLOCATE PREPARE studio_sequence;
SET @studio_next_publication=(SELECT COALESCE(MAX(id),0)+1 FROM game_release);
SET @studio_publication_sequence=CONCAT('ALTER TABLE project_publication AUTO_INCREMENT=',@studio_next_publication);
PREPARE studio_sequence FROM @studio_publication_sequence;
EXECUTE studio_sequence;
DEALLOCATE PREPARE studio_sequence;
SET @studio_review_sequence=CONCAT('ALTER TABLE project_review AUTO_INCREMENT=',@studio_next_publication);
PREPARE studio_sequence FROM @studio_review_sequence;
EXECUTE studio_sequence;
DEALLOCATE PREPARE studio_sequence;
