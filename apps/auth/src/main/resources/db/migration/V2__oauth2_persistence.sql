CREATE TABLE oauth2_registered_client
(
    id                            VARCHAR(100)  NOT NULL,
    client_id                     VARCHAR(100)  NOT NULL,
    client_id_issued_at           TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    client_secret                 VARCHAR(200)  NULL,
    client_secret_expires_at      TIMESTAMP(6)  NULL,
    client_name                   VARCHAR(200)  NOT NULL,
    client_authentication_methods VARCHAR(1000) NOT NULL,
    authorization_grant_types     VARCHAR(1000) NOT NULL,
    redirect_uris                 VARCHAR(1000) NULL,
    post_logout_redirect_uris     VARCHAR(1000) NULL,
    scopes                        VARCHAR(1000) NOT NULL,
    client_settings               VARCHAR(2000) NOT NULL,
    token_settings                VARCHAR(2000) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_oauth2_client_id (client_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE oauth2_authorization
(
    id                                  VARCHAR(100) NOT NULL,
    registered_client_id                VARCHAR(100) NOT NULL,
    principal_name                      VARCHAR(200) NOT NULL,
    authorization_grant_type            VARCHAR(100) NOT NULL,
    authorized_scopes                   VARCHAR(1000) NULL,
    attributes                          BLOB NULL,
    state                               VARCHAR(500) NULL,
    authorization_code_value            BLOB NULL,
    authorization_code_issued_at        TIMESTAMP(6) NULL,
    authorization_code_expires_at       TIMESTAMP(6) NULL,
    authorization_code_metadata         BLOB NULL,
    access_token_value                  BLOB NULL,
    access_token_issued_at              TIMESTAMP(6) NULL,
    access_token_expires_at             TIMESTAMP(6) NULL,
    access_token_metadata               BLOB NULL,
    access_token_type                   VARCHAR(100) NULL,
    access_token_scopes                 VARCHAR(1000) NULL,
    oidc_id_token_value                 BLOB NULL,
    oidc_id_token_issued_at             TIMESTAMP(6) NULL,
    oidc_id_token_expires_at            TIMESTAMP(6) NULL,
    oidc_id_token_metadata              BLOB NULL,
    refresh_token_value                 BLOB NULL,
    refresh_token_issued_at             TIMESTAMP(6) NULL,
    refresh_token_expires_at            TIMESTAMP(6) NULL,
    refresh_token_metadata              BLOB NULL,
    user_code_value                     BLOB NULL,
    user_code_issued_at                 TIMESTAMP(6) NULL,
    user_code_expires_at                TIMESTAMP(6) NULL,
    user_code_metadata                  BLOB NULL,
    device_code_value                   BLOB NULL,
    device_code_issued_at               TIMESTAMP(6) NULL,
    device_code_expires_at              TIMESTAMP(6) NULL,
    device_code_metadata                BLOB NULL,
    PRIMARY KEY (id),
    KEY idx_oauth2_principal (principal_name),
    CONSTRAINT fk_oauth2_authorization_client FOREIGN KEY (registered_client_id)
        REFERENCES oauth2_registered_client (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE oauth2_authorization_consent
(
    registered_client_id VARCHAR(100)  NOT NULL,
    principal_name       VARCHAR(200)  NOT NULL,
    authorities          VARCHAR(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name),
    CONSTRAINT fk_oauth2_consent_client FOREIGN KEY (registered_client_id)
        REFERENCES oauth2_registered_client (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE auth_signing_key
(
    key_name    VARCHAR(50) NOT NULL,
    private_jwk LONGTEXT    NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (key_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
