# 작업 기준

- 현재 재작성 완료 모듈은 `auth`, `gateway`, `studio`, `review`, `catalog`다. Studio·Review 재작성 완료일은 2026-10-14, Catalog는 2026-10-15다.
- 신규 구현과 수정·검증은 완료된 모듈의 계약을 기준으로 한다.
- 미완료 모듈은 호환성 유지, 설계 제약, 구현 변경 또는 검증의 기준으로 삼지 않는다.
- Studio의 소유자는 Auth JWT의 `sub`로 직접 식별한다.
- 미완료 외부 업무의 요청·결과는 Studio의 이벤트 계약 경계에서 검증한다.
- Studio는 출시용 상세정보·빌드 관리, 심사 요청·재요청, 출시 요청·재요청을 담당한다.
- 심사 판정과 실제 출시는 외부 업무다. Studio는 외부 결과를 요청 기록에 반영하며 공개 상품·공개본을 관리하지 않는다.
- Review는 불변 Project 스냅샷의 수동 승인·반려와 결과 발행을 담당한다. 범위와 검증 기록은 `docs/review-simplification-plan.md`를 따른다.
- Catalog는 Studio 출시 요청의 불변 스냅샷을 공개본으로 확정하고 공개 상품 조회·출시 결과 발행을 담당한다. 범위와 검증 기록은 `docs/catalog-mvp-plan.md`를 따른다.
