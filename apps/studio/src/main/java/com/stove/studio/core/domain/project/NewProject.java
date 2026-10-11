package com.stove.studio.core.domain.project;

/**
 *  프로젝트 생성 입력. HTTP 요청 형식과 무관한 core 입력 모델이다.
 *
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param title 게임 또는 상점 페이지에 표시할 제목.
 * @param sellerId 판매자를 나타내는 워크스페이스 식별자.
 * @param price 상품 한 개의 가격으로, 최소 화폐 단위의 정수.
 * @param currency 금액의 통화 코드.
 * @param selfRated 자체등급분류 경로로 신청하는지 여부.
 * @param productKind 기본 게임·체험판·DLC·에디션·번들 구분.
 * @param parentGameId 파생 프로젝트가 참조하는 원본 게임 프로젝트 식별자.
 * @param editionName 에디션 구분을 위해 표시하는 이름.
 * @param bundleGameIds 번들에 포함할 게임 프로젝트 식별자 목록.
 */
public record NewProject(
        String productCode,
        String title,
        Long sellerId,
        long price,
        String currency,
        boolean selfRated,
        ProductKind productKind,
        Long parentGameId,
        String editionName,
        java.util.List<Long> bundleGameIds
) {
    public NewProject(String productCode, String title, Long sellerId, long price,
                      String currency, boolean selfRated) {
        this(productCode, title, sellerId, price, currency, selfRated,
                ProductKind.BASIC, null, null, java.util.List.of());
    }

    public NewProject {
        productKind = productKind == null ? ProductKind.BASIC : productKind;
        bundleGameIds = bundleGameIds == null ? java.util.List.of() : java.util.List.copyOf(bundleGameIds);
    }
}
