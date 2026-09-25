package com.stove.studio.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.studio.core.domain.GameBuild;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CiStatusPublisherService {
    private final ObjectMapper objectMapper;
    private final String githubToken;
    private final String gitlabToken;
    private final String githubApi;
    private final String gitlabApi;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public CiStatusPublisherService(ObjectMapper objectMapper,
                                    @Value("${stove.ci.status.github-token:}") String githubToken,
                                    @Value("${stove.ci.status.gitlab-token:}") String gitlabToken,
                                    @Value("${stove.ci.status.github-api:https://api.github.com}") String githubApi,
                                    @Value("${stove.ci.status.gitlab-api:https://gitlab.com/api/v4}") String gitlabApi) {
        this.objectMapper = objectMapper;
        this.githubToken = githubToken;
        this.gitlabToken = gitlabToken;
        this.githubApi = githubApi;
        this.gitlabApi = gitlabApi;
    }

    @Async
    public void publish(GameBuild build, boolean success, String description) {
        if (build.getCommitSha() == null || build.getRepository() == null || build.getCiProvider() == null) return;
        try {
            if ("GITHUB".equalsIgnoreCase(build.getCiProvider()) && !githubToken.isBlank()) {
                publishGithub(build, success, description);
            } else if ("GITLAB".equalsIgnoreCase(build.getCiProvider()) && !gitlabToken.isBlank()) {
                publishGitlab(build, success, description);
            }
        } catch (Exception exception) {
            log.warn("CI 상태 게시 실패 buildId={} provider={}",
                    build.getId(), build.getCiProvider(), exception);
        }
    }

    private void publishGithub(GameBuild build, boolean success, String description) throws Exception {
        String repository = normalizeRepository(build.getRepository(), "github.com");
        String payload = objectMapper.writeValueAsString(Map.of(
                "state", success ? "success" : "failure",
                "context", "ESD/build-validation",
                "description", truncate(description, 140)));
        send(HttpRequest.newBuilder(URI.create(githubApi + "/repos/" + repository
                        + "/statuses/" + build.getCommitSha()))
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build());
    }

    private void publishGitlab(GameBuild build, boolean success, String description) throws Exception {
        String repository = normalizeRepository(build.getRepository(), "gitlab.com");
        String encoded = URLEncoder.encode(repository, StandardCharsets.UTF_8);
        String payload = objectMapper.writeValueAsString(Map.of(
                "state", success ? "success" : "failed",
                "name", "ESD/build-validation",
                "description", truncate(description, 255),
                "ref", build.getSourceRef() == null ? "" : build.getSourceRef()));
        send(HttpRequest.newBuilder(URI.create(gitlabApi + "/projects/" + encoded
                        + "/statuses/" + build.getCommitSha()))
                .header("PRIVATE-TOKEN", gitlabToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build());
    }

    private void send(HttpRequest request) throws Exception {
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("CI status API returned HTTP " + response.statusCode());
        }
    }

    private String normalizeRepository(String value, String host) {
        String normalized = value.replace("https://" + host + "/", "")
                .replace("http://" + host + "/", "");
        return normalized.endsWith(".git") ? normalized.substring(0, normalized.length() - 4) : normalized;
    }

    private String truncate(String value, int length) {
        return value.substring(0, Math.min(value.length(), length));
    }
}
