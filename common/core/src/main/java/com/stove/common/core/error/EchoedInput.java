package com.stove.common.core.error;

/**
 * 요청이 보낸 문자열을 오류 메시지나 로그에 <b>되실을 때</b> 통과시키는 자리. [D-025]
 * 값을 인용하는 쪽이 아니라 여기 한 곳에서 막는다 — 되싣는 자리는 앞으로도 늘어난다.
 * docs/code-notes.md
 */
public final class EchoedInput {

    /** 길이 상한. 되싣는 값은 원래 짧다 — 이보다 길면 메시지를 채우려는 시도에 가깝다. */
    private static final int MAX_LENGTH = 40;

    private static final String TRUNCATED = "…";

    private EchoedInput() {
    }

    /**
     * 되싣기 안전한 형태로 만든다 — 제어문자를 지우고 상한에서 자른다.
     * <b>치환이 아니라 삭제다</b> — 이유는 docs/code-notes.md
     */
    public static String safe(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(Math.min(raw.length(), MAX_LENGTH + 1));
        for (int i = 0; i < raw.length() && cleaned.length() <= MAX_LENGTH; i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) {
                cleaned.append(c);
            }
        }
        // 한 글자 넘겨 세는 이유 — 정확히 상한인 값에 잘림 표시를 붙이지 않으려고.
        return cleaned.length() <= MAX_LENGTH
                ? cleaned.toString()
                : cleaned.substring(0, MAX_LENGTH) + TRUNCATED;
    }
}
