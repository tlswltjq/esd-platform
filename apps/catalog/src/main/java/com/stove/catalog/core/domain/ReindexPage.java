package com.stove.catalog.core.domain;

/**
 * 재색인 한 페이지의 결과.
 *
 * <p>재색인은 페이지마다 독립 커밋이라 반복이 트랜잭션 밖에 있어야 한다. 그 반복을 도는
 * 조율 계층이 "어디까지 갔고 더 남았는가"를 알아야 하므로 값으로 돌려준다.
 *
 * @param published 이 페이지에서 발행한 수
 * @param lastId    다음 페이지의 커서. 발행한 것이 없으면 넘겨받은 커서 그대로다
 * @param hasNext   더 남았을 수 있는가(페이지가 가득 찼는가)
 */
public record ReindexPage(int published, long lastId, boolean hasNext) {

    public static ReindexPage empty(long lastId) {
        return new ReindexPage(0, lastId, false);
    }
}
