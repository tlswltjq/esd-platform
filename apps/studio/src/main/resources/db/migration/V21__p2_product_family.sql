ALTER TABLE game_project
    ADD COLUMN product_kind VARCHAR(20) NOT NULL DEFAULT 'BASIC' AFTER reject_reason,
    ADD COLUMN parent_game_id BIGINT NULL AFTER product_kind,
    ADD COLUMN edition_name VARCHAR(100) NULL AFTER parent_game_id;

CREATE INDEX idx_game_project_family ON game_project (parent_game_id, product_kind, id);

CREATE TABLE bundle_component
(
    id                BIGINT NOT NULL AUTO_INCREMENT,
    bundle_game_id    BIGINT NOT NULL,
    component_game_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bundle_component (bundle_game_id, component_game_id),
    KEY idx_bundle_component_game (component_game_id, bundle_game_id),
    CONSTRAINT fk_bundle_owner FOREIGN KEY (bundle_game_id) REFERENCES game_project (id),
    CONSTRAINT fk_bundle_member FOREIGN KEY (component_game_id) REFERENCES game_project (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
