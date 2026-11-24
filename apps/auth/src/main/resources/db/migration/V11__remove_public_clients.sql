-- 결정 220: 공개 클라이언트(studio-web·swagger-ui)를 없앤다. OAuth 클라이언트는 gateway-bff 하나다.
-- 앞선 시작에서 저장된 등록 행과 그 행을 가리키는 인가·동의·Refresh 계열을 외래 키 순서대로 지운다.
-- gateway-bff와 그 기록은 건드리지 않는다.
DELETE FROM refresh_token_history
WHERE authorization_id IN (
    SELECT authorization_id FROM refresh_token_family
    WHERE client_id IN (SELECT id FROM oauth2_registered_client WHERE client_id IN ('studio-web', 'swagger-ui')));

DELETE FROM refresh_token_family
WHERE client_id IN (SELECT id FROM oauth2_registered_client WHERE client_id IN ('studio-web', 'swagger-ui'));

DELETE FROM oauth2_authorization
WHERE registered_client_id IN (SELECT id FROM oauth2_registered_client WHERE client_id IN ('studio-web', 'swagger-ui'));

DELETE FROM oauth2_authorization_consent
WHERE registered_client_id IN (SELECT id FROM oauth2_registered_client WHERE client_id IN ('studio-web', 'swagger-ui'));

DELETE FROM oauth2_registered_client WHERE client_id IN ('studio-web', 'swagger-ui');
