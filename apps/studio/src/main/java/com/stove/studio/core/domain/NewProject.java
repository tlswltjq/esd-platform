package com.stove.studio.core.domain;

/** 프로젝트 생성 입력. HTTP 요청 형식과 무관한 core 입력 모델이다. */
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
