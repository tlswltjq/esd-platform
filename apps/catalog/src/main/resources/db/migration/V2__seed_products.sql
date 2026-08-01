-- 로컬 개발용 샘플 데이터 (자체 판매 1건, 입점 판매 2건)
INSERT INTO product (product_code, name, seller_id, price, currency, status, rating_code, created_at, updated_at)
VALUES ('GAME-LOA-DELUXE', '로스트아크 디럭스 패키지', 1, 39000, 'KRW', 'ON_SALE', '15', NOW(6), NOW(6)),
       ('GAME-INDIE-001', '인디 플랫포머 데모+', 1001, 12000, 'KRW', 'ON_SALE', 'ALL', NOW(6), NOW(6)),
       ('GAME-INDIE-002', '픽셀 로그라이크', 1002, 22000, 'KRW', 'APPROVED', '12', NOW(6), NOW(6));
