package com.stove.common.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 결정 135: 이 저장소가 직접 만드는 이미지의 Compose 기본 이름은 Docker Hub로 해석되지 않는다.
 *
 * <p>registry 주소가 없는 이름({@code stove/gateway})은 Docker Hub의 {@code stove} 계정을 가리키고, 그 계정은
 * 이 프로젝트와 무관한 다른 사람 것이다. {@code docker compose up}은 로컬에 없는 이미지를 그 주소에서 받으므로,
 * 기본값은 {@code localhost/stove}여야 한다. 배포는 {@code STOVE_JIB_REGISTRY}로 우리 registry를 지정한다.
 */
class LocalImageRegistryContractTest {

    private static final String DEFAULT_REGISTRY = "localhost/stove/";

    @Test
    @DisplayName("변수가 없을 때 Compose의 어떤 이미지도 Docker Hub의 stove 계정으로 해석되지 않는다")
    void noDefaultImageResolvesToDockerHubStove() {
        List<String> checked = new ArrayList<>();
        for (Path file : composeFiles()) {
            for (Map.Entry<String, String> image : images(file).entrySet()) {
                String resolved = resolve(image.getValue(), Map.of());
                checked.add(resolved);
                assertThat(resolved)
                        .as("%s/%s 기본 이미지", file.getFileName(), image.getKey())
                        .doesNotStartWith("stove/")
                        .doesNotStartWith("docker.io/stove/")
                        .doesNotStartWith("index.docker.io/stove/");
            }
        }
        assertThat(checked).as("검사한 이미지").isNotEmpty();
    }

    @Test
    @DisplayName("직접 만드는 이미지는 기본 localhost/stove, 배포는 STOVE_JIB_REGISTRY를 따른다")
    void selfBuiltImagesDefaultToLocalRegistry() {
        List<String> selfBuilt = new ArrayList<>();
        for (Path file : composeFiles()) {
            for (Map.Entry<String, String> image : images(file).entrySet()) {
                if (!image.getValue().contains("STOVE_JIB_REGISTRY")) {
                    continue;
                }
                String label = file.getFileName() + "/" + image.getKey();
                selfBuilt.add(label);
                assertThat(resolve(image.getValue(), Map.of())).as("%s 기본값", label).startsWith(DEFAULT_REGISTRY);
                assertThat(resolve(image.getValue(), Map.of("STOVE_JIB_REGISTRY", "ghcr.io/team")))
                        .as("%s 배포 registry", label).startsWith("ghcr.io/team/");
            }
        }
        // 앱 11개 + mock-pg + MinIO
        assertThat(selfBuilt).as("직접 만드는 이미지").hasSize(13);
    }

    private static List<Path> composeFiles() {
        try (Stream<Path> files = Files.list(repoRoot())) {
            return files.filter(path -> path.getFileName().toString().matches("docker-compose.*\\.ya?ml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Compose 파일을 읽을 수 없다", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> images(Path file) {
        Map<String, String> images = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> yaml = new Yaml().load(in);
            Object services = yaml == null ? null : yaml.get("services");
            if (services instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> service : map.entrySet()) {
                    if (service.getValue() instanceof Map<?, ?> definition && definition.get("image") != null) {
                        images.put(service.getKey().toString(), definition.get("image").toString());
                    }
                }
            }
            return images;
        } catch (IOException e) {
            throw new IllegalStateException("YAML 을 읽을 수 없다: " + file, e);
        }
    }

    /** Compose의 {@code ${NAME}}·{@code ${NAME:-기본값}}(중첩 포함)을 주어진 환경으로 푼다. */
    private static String resolve(String text, Map<String, String> environment) {
        StringBuilder resolved = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            int start = text.indexOf("${", index);
            if (start < 0) {
                resolved.append(text, index, text.length());
                break;
            }
            resolved.append(text, index, start);
            int end = closingBrace(text, start + 1);
            String body = text.substring(start + 2, end);
            int separator = body.indexOf(":-");
            String name = separator < 0 ? body : body.substring(0, separator);
            String fallback = separator < 0 ? "" : body.substring(separator + 2);
            String value = environment.getOrDefault(name, "");
            resolved.append(value.isEmpty() ? resolve(fallback, environment) : value);
            index = end + 1;
        }
        return resolved.toString();
    }

    private static int closingBrace(String text, int open) {
        int depth = 0;
        for (int index = open; index < text.length(); index++) {
            if (text.charAt(index) == '{') {
                depth++;
            } else if (text.charAt(index) == '}' && --depth == 0) {
                return index;
            }
        }
        throw new IllegalStateException("닫는 } 가 없다: " + text);
    }

    private static Path repoRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && Files.notExists(candidate.resolve("settings.gradle"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("settings.gradle 을 찾지 못했다 — 리포 루트를 알 수 없다");
        }
        return candidate;
    }
}
