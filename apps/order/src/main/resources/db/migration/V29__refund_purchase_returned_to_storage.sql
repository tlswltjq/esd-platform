-- 결정 98: 확정 실패·승인 정정 복구 때 구매분이 보관함으로 돌아갔는지 환불 상세에 보여 준다.
ALTER TABLE order_refund ADD COLUMN purchase_returned_to_storage BOOLEAN NOT NULL DEFAULT FALSE;
