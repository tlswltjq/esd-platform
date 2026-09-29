CREATE TABLE role_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subject VARCHAR(64) NOT NULL,
    actor VARCHAR(80) NOT NULL,
    action VARCHAR(40) NOT NULL,
    roles VARCHAR(200) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    INDEX idx_role_audit_subject (subject, occurred_at)
);
