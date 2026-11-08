package com.stove.common.testcontainers;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 결정 135: 이 저장소가 직접 만드는 이미지의 이름 규칙.
 *
 * <p>registry 주소가 없는 이름은 Docker Hub로 해석되고, Docker Hub의 {@code stove}는 이 프로젝트와 무관한
 * 다른 사람 계정이다. 그래서 기본 registry는 Docker Hub로 해석되지 않는 {@code localhost/stove}이고,
 * {@code stove} 계정을 가리키는 이름은 받지 않고 거절한다. 루트 build.gradle과
 * tools/verification/support/image_names.py가 같은 규칙을 쓴다.
 */
final class LocalImageNames {

    static final String DEFAULT_REGISTRY = "localhost/stove";

    private static final Set<String> DOCKER_HUB_HOSTS = Set.of("docker.io", "index.docker.io", "registry-1.docker.io");

    private LocalImageNames() {
    }

    /** 이미지 이름이 Docker Hub의 stove 계정을 가리키면 거절한다. */
    static String requireNotDockerHubStove(String setting, String image) {
        if (dockerHubStove(image)) {
            throw refusal(setting, image, replacement(image));
        }
        return image;
    }

    /** registry 값이 Docker Hub의 stove 계정을 가리키면 거절한다. */
    static String requireRegistryNotDockerHubStove(String setting, String registry) {
        if (dockerHubStove(registry + "/image")) {
            throw refusal(setting, registry, DEFAULT_REGISTRY);
        }
        return registry;
    }

    static boolean dockerHubStove(String image) {
        List<String> parts = repository(image);
        String first = parts.get(0);
        if (parts.size() > 1 && (first.contains(".") || first.contains(":") || first.equals("localhost"))) {
            if (!DOCKER_HUB_HOSTS.contains(first.toLowerCase(Locale.ROOT))) {
                return false;
            }
            parts = parts.subList(1, parts.size());
        }
        return parts.size() > 1 && parts.get(0).equals("stove");
    }

    private static List<String> repository(String image) {
        String name = image.strip().split("@", 2)[0];
        int slash = name.lastIndexOf('/');
        String last = name.substring(slash + 1).split(":", 2)[0];
        String repository = slash < 0 ? last : name.substring(0, slash) + "/" + last;
        return Arrays.asList(repository.split("/", -1));
    }

    private static String replacement(String image) {
        List<String> parts = Arrays.asList(image.strip().split("/", -1));
        if (DOCKER_HUB_HOSTS.contains(parts.get(0).toLowerCase(Locale.ROOT))) {
            parts = parts.subList(1, parts.size());
        }
        return DEFAULT_REGISTRY + "/" + String.join("/", parts.subList(1, parts.size()));
    }

    private static IllegalStateException refusal(String setting, String value, String replacement) {
        return new IllegalStateException(setting + "=" + value
                + " 는 Docker Hub의 다른 사람 계정(stove)을 가리킨다(결정 135). " + replacement + " 로 바꾼다.");
    }
}
