ALTER TABLE game_build
    ADD COLUMN delta_from_version VARCHAR(30) NULL AFTER architecture;

CREATE TABLE submission_build (
    id BIGINT NOT NULL AUTO_INCREMENT,
    submission_id BIGINT NOT NULL,
    build_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_submission_build (submission_id, build_id),
    CONSTRAINT fk_submission_build_submission FOREIGN KEY (submission_id) REFERENCES submission (id),
    CONSTRAINT fk_submission_build_build FOREIGN KEY (build_id) REFERENCES game_build (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO submission_build (submission_id, build_id)
SELECT id, build_id FROM submission;
