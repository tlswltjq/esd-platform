ALTER TABLE product
    ADD COLUMN current_release_id BIGINT NULL AFTER game_id,
    ADD COLUMN current_build_id BIGINT NULL AFTER current_release_id,
    ADD COLUMN metadata_revision BIGINT NULL AFTER current_build_id,
    ADD KEY idx_product_release (current_release_id);

-- 과거 ReviewApproved만으로 ON_SALE이 된 행은 릴리스 근거가 없으므로 공개 상태에서 내린다.
UPDATE product
SET status = 'APPROVED'
WHERE status = 'ON_SALE'
  AND current_release_id IS NULL;
