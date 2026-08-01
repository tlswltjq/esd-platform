-- studio 게임 프로젝트와의 연결 고리 추가 (크리에이터 트랙 합류)
ALTER TABLE product
    ADD COLUMN game_id BIGINT NULL AFTER product_code,
    ADD KEY idx_product_game (game_id);
