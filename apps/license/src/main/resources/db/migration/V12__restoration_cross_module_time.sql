-- D-052: 복구·승인 정정의 처리 시각(restored_at, License 시계)과 요청 발생 시각(request_occurred_at, Order 시계)은
-- 서로 다른 호스트 시계라 선후를 강제하지 않는다(requirements R8-3). 같은 정지인지는 식별자 FK·유일 키로 대조한다.
-- 두 컬럼의 값과 뜻은 그대로 둔다. 기존 행은 옛 CHECK를 이미 만족하므로 바뀌지 않는다.
ALTER TABLE license_restoration DROP CHECK chk_license_restoration_time;
ALTER TABLE license_approval_correction DROP CHECK chk_license_correction_time;
