ALTER TABLE game_build
    ADD COLUMN duplicate_of_build_id BIGINT NULL AFTER validated_at,
    ADD KEY idx_build_actual_checksum (actual_checksum, status, id);
