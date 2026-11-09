package com.stove.common.test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;

/**
 * 배포 계약 테스트가 함께 읽는 설정 파일(앱 {@code application.yml}·Compose)의 도우미.
 *
 * <p>배포 계약은 앱 하나가 아니라 앱 집합과 Compose 에 걸친 성질이라 소스가 아니라 파일을 읽는다
 * ({@link DeploymentShutdownContractTest}·{@link DatabaseStartupWaitContractTest}).
 */
final class DeploymentFiles {

    static final String APPS_COMPOSE = "docker-compose.apps.yml";

    private DeploymentFiles() {
    }

    /**
     * 리포 루트를 찾는다.
     *
     * <p>테스트의 작업 디렉터리는 모듈 디렉터리라({@code common/test}) 상대 경로를 박으면
     * 모듈을 옮기는 순간 조용히 깨진다. {@code settings.gradle} 이 있는 곳이 루트라는 사실만 쓴다.
     */
    static Path repoRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && Files.notExists(candidate.resolve("settings.gradle"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("settings.gradle 을 찾지 못했다 — 리포 루트를 알 수 없다");
        }
        return candidate;
    }

    static List<Path> appDirectories() {
        try (Stream<Path> apps = Files.list(repoRoot().resolve("apps"))) {
            return apps.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve("src/main/resources/application.yml")))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("apps/ 를 읽을 수 없다", e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> loadYaml(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> loaded = new Yaml().load(in);
            return loaded == null ? Map.of() : loaded;
        } catch (IOException e) {
            throw new IllegalStateException("YAML 을 읽을 수 없다: " + path, e);
        }
    }

    /** {@code a.b.c} 를 중첩 맵에서 꺼낸다. 없으면 null. */
    @SuppressWarnings("unchecked")
    static Object at(Map<String, Object> yaml, String dottedPath) {
        Object current = yaml;
        for (String key : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(key);
        }
        return current;
    }

    /**
     * {@code 30s} · {@code 2m} · {@code 45} 를 읽는다.
     *
     * <p>compose 와 스프링이 쓰는 표기가 다르므로 양쪽을 같은 자리에서 받는다.
     * 단위가 없으면 초로 읽는다(compose 의 규약이다).
     */
    static Duration parseDuration(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        if (text.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2).trim()));
        }
        if (text.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1).trim()));
        }
        if (text.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1).trim()));
        }
        return Duration.ofSeconds(Long.parseLong(text));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }
}
