ALTER TABLE game_release
    ADD COLUMN channel VARCHAR(20) NOT NULL DEFAULT 'LIVE' AFTER rating_revision_id;
