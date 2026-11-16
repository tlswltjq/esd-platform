-- 결정 155 발행 쪽(D-055·D-056): lockPendingBatch가 같은 키의 앞 행이 DEAD이거나 재시도 대기인지
-- 하위 조회로 확인한다. 그 하위 조회가 키 안에서 id 순으로 찾도록 인덱스를 둔다. 행은 바꾸지 않는다.
ALTER TABLE outbox_event ADD INDEX idx_outbox_key_order (partition_key, id);
