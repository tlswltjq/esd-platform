CREATE TABLE audit_log
(
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    actor       VARCHAR(100)  NOT NULL,
    action      VARCHAR(60)   NOT NULL,
    target_type VARCHAR(50)   NOT NULL,
    target_id   VARCHAR(100)  NOT NULL,
    details     VARCHAR(1000) NULL,
    occurred_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_target (target_type, target_id, id),
    KEY idx_audit_actor (actor, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
