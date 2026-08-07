package com.stove.archfixture.consumergroup.guardless;

/**
 * 준수 — Inbox 가드를 쓰지 않는 앱(store·download 모사).
 *
 * <p>문서 ID 고정 upsert 라 연산 자체가 멱등이므로 멱등 키가 없다(결정 8).
 * 대조할 두 번째 출처가 없으니 규칙은 이 앱을 건너뛰어야 한다.
 */
public class GuardlessService {

    public void reindex(String documentId, String payload) {
    }
}
