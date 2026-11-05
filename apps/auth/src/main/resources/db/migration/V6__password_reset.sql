CREATE TABLE password_reset_token
(
    token_hash CHAR(64)     NOT NULL,
    subject    VARCHAR(64)  NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed   BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (token_hash),
    INDEX idx_password_reset_subject (subject)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
