ALTER TABLE game_release
    ADD COLUMN change_type VARCHAR(30) NOT NULL DEFAULT 'INITIAL' AFTER channel,
    ADD COLUMN publish_time_zone VARCHAR(50) NOT NULL DEFAULT 'UTC' AFTER published_at,
    ADD COLUMN smoke_test_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER publish_time_zone,
    ADD COLUMN smoke_tested_at DATETIME(6) NULL AFTER smoke_test_status,
    ADD COLUMN smoke_test_failure VARCHAR(500) NULL AFTER smoke_tested_at;

CREATE INDEX idx_release_channel_current
    ON game_release (game_id, channel, status, published_at);
