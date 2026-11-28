package com.stove.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 앱 프로세스 하나에 넘길 환경 변수를 고른다. 프로젝트의 비밀 값은 그 앱이 받기로 한 것만 넘긴다.
 *
 * <p>허용 목록은 {@code tools/verification/support/service_credentials.json} 한 곳에 있다. Compose의 키 수신자를
 * 검사하는 파이썬 쪽({@code service_credentials.py})도 같은 파일을 읽는다.
 */
final class ServiceCredentials {

    private static final String RULES = "tools/verification/support/service_credentials.json";

    private final Map<String, Set<String>> allowed;
    private final List<String> prefixes;
    private final List<String> suffixes;

    private ServiceCredentials(Map<String, Set<String>> allowed, List<String> prefixes, List<String> suffixes) {
        this.allowed = allowed;
        this.prefixes = prefixes;
        this.suffixes = suffixes;
    }

    static ServiceCredentials load(Path repository) {
        try {
            JsonNode rules = new ObjectMapper().readTree(repository.resolve(RULES).toFile());
            Map<String, Set<String>> allowed = new LinkedHashMap<>();
            rules.path("allowed").fields().forEachRemaining(entry -> allowed.put(entry.getKey(), strings(entry.getValue())));
            for (StackApp app : StackApp.values()) {
                if (!allowed.containsKey(app.module())) {
                    throw new IllegalStateException(RULES + " 에 " + app.module() + " 의 허용 목록이 없다");
                }
            }
            return new ServiceCredentials(allowed, List.copyOf(strings(rules.path("prefixes"))),
                    List.copyOf(strings(rules.path("suffixes"))));
        } catch (IOException failure) {
            throw new IllegalStateException(RULES + " 를 읽지 못했다", failure);
        }
    }

    /** {@code inherited}에서 이 앱이 받지 않는 프로젝트 비밀을 뺀 환경. */
    Map<String, String> environmentFor(StackApp app, Map<String, String> inherited) {
        Set<String> names = allowed.get(app.module());
        Map<String, String> selected = new LinkedHashMap<>();
        inherited.forEach((name, value) -> {
            if (!projectCredential(name) || names.contains(name)) {
                selected.put(name, value);
            }
        });
        return selected;
    }

    /** 환경에 든 프로젝트 비밀의 이름(값은 아님). */
    Set<String> credentialNames(Map<String, String> environment) {
        Set<String> names = new TreeSet<>();
        for (String name : environment.keySet()) {
            if (projectCredential(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private boolean projectCredential(String name) {
        if (name.equals("DB_PASSWORD")) {
            return true;
        }
        return prefixes.stream().anyMatch(name::startsWith) && suffixes.stream().anyMatch(name::endsWith);
    }

    private static Set<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return new TreeSet<>(values);
    }
}
