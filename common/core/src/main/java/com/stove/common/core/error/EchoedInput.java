package com.stove.common.core.error;

/**
 * 요청이 보낸 문자열을 오류 메시지나 로그에 <b>되실을 때</b> 통과시키는 자리. [D-025]
 *
 * <p><b>왜 필요한가</b> — 거절 사유는 부르는 쪽이 무엇을 고쳐야 하는지 알려 줘야 하므로
 * 대개 요청 값을 그대로 인용한다. 그런데 그 메시지는 응답으로도 나가고 로그로도 나간다.
 * 인용한 값에 개행이 있으면 <b>로그 한 줄이 두 줄이 된다</b> — 뒤 줄은 공격자가 쓴 것이고
 * 형식만 맞으면 진짜 로그와 구분되지 않는다. 길이 제한이 없으면 한 요청이 로그 한 화면을 먹는다.
 *
 * <p>인증 없이 열린 경로(게이트웨이의 {@code catalog-public})에서는 이것을 요청 속도로
 * 밀어 넣을 수 있다. 그래서 값을 인용하는 쪽이 아니라 <b>여기 한 곳</b>에서 막는다 —
 * 되싣는 자리는 앞으로도 늘어난다.
 */
public final class EchoedInput {

    /**
     * 길이 상한. 진단에 필요한 만큼만 남긴다.
     *
     * <p>정렬 키·속성 이름처럼 되싣는 값은 원래 짧다. 이보다 길다는 것은
     * 값이 아니라 <b>메시지를 채우려는 시도</b>에 가깝다.
     */
    private static final int MAX_LENGTH = 40;

    private static final String TRUNCATED = "…";

    private EchoedInput() {
    }

    /**
     * 되싣기 안전한 형태로 만든다 — 제어문자를 지우고 상한에서 자른다.
     *
     * <p>제어문자는 지우지 않고 치환하지 않는다. 개행을 {@code \\n} 같은 표기로 바꾸면
     * 읽는 사람은 그것이 원래 문자였는지 표기였는지 알 수 없고, 위조 시도를 그대로 보여 줄
     * 이유도 없다. 남은 글자만으로 어떤 키를 보냈는지는 충분히 드러난다.
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
        // 상한을 한 글자 넘겨서 세는 이유 — 정확히 상한 길이인 값에까지 잘렸다는 표시를 붙이지 않는다.
        return cleaned.length() <= MAX_LENGTH
                ? cleaned.toString()
                : cleaned.substring(0, MAX_LENGTH) + TRUNCATED;
    }
}
