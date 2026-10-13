CREATE TABLE refresh_token_family (
    authorization_id VARCHAR(100) NOT NULL PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_refresh_subject (subject)
);
CREATE TABLE refresh_token_history (
    token_hash CHAR(64) NOT NULL PRIMARY KEY,
    authorization_id VARCHAR(100) NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_refresh_authorization (authorization_id),
    FOREIGN KEY (authorization_id) REFERENCES refresh_token_family(authorization_id)
);
