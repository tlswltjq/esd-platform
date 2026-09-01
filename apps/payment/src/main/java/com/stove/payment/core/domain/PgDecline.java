package com.stove.payment.core.domain;

/**
 * PG 승인 거절 사실. 승인과 달리 <b>금액도 멱등키도 없다</b> — 돈이 움직이지 않았다.
 * docs/code-notes.md
 */
public record PgDecline(String orderNo, String pgTxId, String reasonCode, String reason) {
}
