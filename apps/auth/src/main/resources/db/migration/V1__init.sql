CREATE TABLE user_account
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    subject       VARCHAR(64)  NOT NULL,
    email         VARCHAR(200) NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account_subject (subject),
    UNIQUE KEY uk_user_account_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE user_account_role
(
    user_account_id BIGINT      NOT NULL,
    role            VARCHAR(20) NOT NULL,
    PRIMARY KEY (user_account_id, role),
    CONSTRAINT fk_user_role_account FOREIGN KEY (user_account_id) REFERENCES user_account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
