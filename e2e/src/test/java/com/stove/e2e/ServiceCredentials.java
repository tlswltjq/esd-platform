package com.stove.e2e;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 앱 프로세스 하나에 넘길 환경 변수를 고른다. 프로젝트의 비밀 값은 그 앱이 받기로 한 것만 넘긴다.
 *
 * <p>규칙은 {@code tools/verification/support/service_credentials.py}와 같다. 두 곳이 함께 있는 동안 허용 목록을
 * 바꾸면 둘 다 고친다.
 */
final class ServiceCredentials {

    private static final Set<String> DB = Set.of("DB_PASSWORD");
    private static final Set<String> BFF = Set.of("BFF_CLIENT_SECRET");
    private static final Set<String> CATALOG_KEYS = Set.of("CATALOG_STORE_SERVICE_KEY", "CATALOG_ORDER_SERVICE_KEY",
            "CATALOG_DOWNLOAD_SERVICE_KEY", "CATALOG_SETTLEMENT_SERVICE_KEY");
    private static final Set<String> READER = Set.of("DOWNLOAD_STORAGE_ACCESS_KEY", "DOWNLOAD_STORAGE_SECRET_KEY");

    private static final Map<StackApp, Set<String>> ALLOWED = Map.ofEntries(
            Map.entry(StackApp.AUTH, union(DB, BFF, Set.of("AUTH_ADMIN_PASSWORD", "AUTH_REVIEWER_PASSWORD"))),
            // 웹 BFF가 OAuth 클라이언트를 갖고, 내부 API 게이트웨이는 릴레이된 Bearer만 검증한다.
            Map.entry(StackApp.WEB_BFF, BFF),
            Map.entry(StackApp.GATEWAY, Set.of()),
            Map.entry(StackApp.STUDIO, union(DB, Set.of("STORAGE_ACCESS_KEY", "STORAGE_SECRET_KEY"))),
            Map.entry(StackApp.REVIEW, union(DB, Set.of("REVIEW_STORAGE_ACCESS_KEY", "REVIEW_STORAGE_SECRET_KEY"))),
            Map.entry(StackApp.CATALOG, union(DB, CATALOG_KEYS, READER,
                    Set.of("CATALOG_DOWNLOAD_STORAGE_ACCESS_KEY", "CATALOG_DOWNLOAD_STORAGE_SECRET_KEY"))),
            Map.entry(StackApp.STORE, union(DB, Set.of("CATALOG_STORE_SERVICE_KEY"))),
            Map.entry(StackApp.ORDER, union(DB, Set.of("CATALOG_ORDER_SERVICE_KEY", "PAYMENT_ORDER_SERVICE_KEY"))),
            Map.entry(StackApp.PAYMENT, union(DB, Set.of("PAYMENT_ORDER_SERVICE_KEY", "PAYMENT_APPROVAL_PAYLOAD_KEY"))),
            Map.entry(StackApp.LICENSE, DB),
            Map.entry(StackApp.DOWNLOAD, union(READER, Set.of("CATALOG_DOWNLOAD_SERVICE_KEY"))),
            Map.entry(StackApp.SETTLEMENT, union(DB, Set.of("CATALOG_SETTLEMENT_SERVICE_KEY"))));

    // 어느 앱도 받지 않는 PG 접두사를 남겨 둔다: 셸에 남은 PG 비밀이 앱에 닿으면 안 된다(결정 remove-pg-adapters).
    private static final List<String> PREFIXES = List.of("CATALOG_", "PAYMENT_", "MOCK_PG_", "PORTONE_",
            "SETTLEMENT_PORTONE_", "TOSSPAY_", "AUTH_", "BFF_", "STORAGE_", "REVIEW_STORAGE_", "DOWNLOAD_STORAGE_");
    private static final List<String> SUFFIXES = List.of("_KEY", "_SECRET", "_PASSWORD");

    private ServiceCredentials() {
    }

    /** {@code inherited}에서 이 앱이 받지 않는 프로젝트 비밀을 뺀 환경. */
    static Map<String, String> environmentFor(StackApp app, Map<String, String> inherited) {
        Set<String> allowed = ALLOWED.get(app);
        Map<String, String> selected = new LinkedHashMap<>();
        inherited.forEach((name, value) -> {
            if (!projectCredential(name) || allowed.contains(name)) {
                selected.put(name, value);
            }
        });
        return selected;
    }

    /** 환경에 든 프로젝트 비밀의 이름(값은 아님). */
    static Set<String> credentialNames(Map<String, String> environment) {
        Set<String> names = new TreeSet<>();
        for (String name : environment.keySet()) {
            if (projectCredential(name)) {
                names.add(name);
            }
        }
        return names;
    }

    static boolean projectCredential(String name) {
        if (name.equals("DB_PASSWORD")) {
            return true;
        }
        return PREFIXES.stream().anyMatch(name::startsWith) && SUFFIXES.stream().anyMatch(name::endsWith);
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> all = new TreeSet<>();
        for (Set<String> set : sets) {
            all.addAll(set);
        }
        return Set.copyOf(all);
    }
}
