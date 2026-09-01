package com.stove.common.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.web.client.RestClient;

/**
 * 생성된 OpenAPI 명세를 리포에 커밋된 스냅샷과 대조한다 — 그 diff 가 곧 API 변경 목록이다.
 * 전용 테스트 클래스를 만들지 않고 {@code *ContextTest} 에 얹는다. docs/code-notes.md
 */
public final class OpenApiSnapshot {

    /** 의도한 변경일 때 스냅샷을 새로 쓴다: {@code ./gradlew integrationTest -Dstove.openapi.update=true} */
    private static final String UPDATE_FLAG = "stove.openapi.update";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private OpenApiSnapshot() {
    }

    /**
     * @param port    {@code @LocalServerPort} 로 받은 실제 포트
     * @param service 스냅샷 파일 이름({@code src/integrationTest/resources/openapi/<service>.json})
     */
    public static void verify(int port, String service) {
        String actual = normalize(fetch(port));
        Path snapshot = Path.of("src", "integrationTest", "resources", "openapi", service + ".json");

        if (Files.notExists(snapshot) || updateRequested()) {
            write(snapshot, actual);
            if (updateRequested()) {
                return;   // 갱신이 목적이었으므로 통과시킨다
            }
            fail("""
                    %s 스냅샷이 없어 새로 만들었다: %s
                    내용이 의도한 API 계약인지 확인하고 커밋하라.""".formatted(service, snapshot));
        }

        assertThat(actual)
                .as("""
                        %s 의 API 명세가 커밋된 스냅샷과 다르다.
                        의도한 변경이면 아래로 갱신하고, 그 diff 를 리뷰에 포함하라:
                          ./gradlew :apps:%s:integrationTest -Dstove.openapi.update=true""", service, service)
                .isEqualTo(read(snapshot));
    }

    private static boolean updateRequested() {
        return Boolean.parseBoolean(System.getProperty(UPDATE_FLAG, "false"));
    }

    private static String fetch(int port) {
        String body = RestClient.create()
                .get()
                .uri("http://localhost:%d/v3/api-docs".formatted(port))
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("/v3/api-docs 가 빈 응답을 돌려줬다 — springdoc 이 안 붙었을 수 있다");
        }
        return body;
    }

    /**
     * 비교 가능한 형태로 고른다 — {@code servers}(임의 포트)를 지우고 키를 정렬한다.
     * docs/code-notes.md
     */
    private static String normalize(String rawJson) {
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            if (root instanceof ObjectNode object) {
                object.remove("servers");
            }
            return MAPPER.writeValueAsString(MAPPER.treeToValue(root, Object.class)) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException("OpenAPI 명세를 파싱할 수 없다", e);
        }
    }

    private static void write(Path snapshot, String content) {
        try {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, content);
        } catch (IOException e) {
            throw new IllegalStateException("스냅샷을 쓸 수 없다: " + snapshot, e);
        }
    }

    private static String read(Path snapshot) {
        try {
            return Files.readString(snapshot);
        } catch (IOException e) {
            throw new IllegalStateException("스냅샷을 읽을 수 없다: " + snapshot, e);
        }
    }
}
