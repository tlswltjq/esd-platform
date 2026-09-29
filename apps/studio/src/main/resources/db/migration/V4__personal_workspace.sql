CREATE TABLE workspace
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    owner_subject VARCHAR(100) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workspace_owner_subject (owner_subject)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO workspace (id, owner_subject, status, created_at, updated_at)
SELECT DISTINCT seller_id,
                CONCAT('legacy-seller:', seller_id),
                'ACTIVE',
                CURRENT_TIMESTAMP(6),
                CURRENT_TIMESTAMP(6)
FROM game_project;

ALTER TABLE game_project
    ADD COLUMN workspace_id BIGINT NULL AFTER title;

UPDATE game_project SET workspace_id = seller_id WHERE workspace_id IS NULL;

ALTER TABLE game_project
    MODIFY workspace_id BIGINT NOT NULL,
    ADD KEY idx_game_project_workspace (workspace_id, id),
    ADD CONSTRAINT fk_game_project_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id);

ALTER TABLE game_project
    DROP INDEX idx_game_project_seller,
    DROP COLUMN seller_id;

-- commerce의 자체 판매자 예약 ID(1)와 개인 workspace ID가 충돌하지 않게 별도 범위를 쓴다.
ALTER TABLE workspace AUTO_INCREMENT = 100000;
