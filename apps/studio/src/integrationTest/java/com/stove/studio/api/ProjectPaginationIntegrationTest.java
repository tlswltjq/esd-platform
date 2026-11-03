package com.stove.studio.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.studio.StudioIntegrationTestBase;
import com.stove.studio.core.domain.build.Build;
import com.stove.studio.core.domain.build.BuildRepository;
import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.domain.project.ProjectRepository;
import com.stove.studio.core.domain.project.StorePage;
import com.stove.studio.core.domain.release.ProjectReleaseRequest;
import com.stove.studio.core.domain.release.ProjectReleaseRequestRepository;
import com.stove.studio.core.domain.review.ProjectReviewRequest;
import com.stove.studio.core.domain.review.ProjectReviewRequestRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class ProjectPaginationIntegrationTest extends StudioIntegrationTestBase {
    @Autowired TestRestTemplate http;
    @Autowired ProjectRepository projects;
    @Autowired BuildRepository builds;
    @Autowired ProjectReviewRequestRepository reviews;
    @Autowired ProjectReleaseRequestRepository releases;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManagerFactory entities;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach
    void authenticateFixtures() {
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String subject = call.getArgument(0, String.class);
            return Jwt.withTokenValue(subject).header("alg", "RS256").subject(subject)
                    .claim("roles", List.of("CREATOR")).build();
        });
    }

    @Test
    void defaultsLimitProjectsAndPagesHaveExactOwnedTotalsAndStableNewestOrder() {
        String owner = UUID.randomUUID().toString();
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 23; index++) ids.add(project(owner).getId());
        project("another-" + owner);
        Collections.reverse(ids);
        Statistics statistics = entities.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        JsonNode first;
        long loaded;
        try {
            first = page("/api/v1/studio/projects", owner);
            loaded = statistics.getEntityLoadCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
        assertThat(loaded).as("only the requested 20 projects are materialized from the database").isEqualTo(20);
        assertThat(first.path("page").asInt()).isZero();
        assertThat(first.path("size").asInt()).isEqualTo(20);
        assertThat(first.path("items").size()).isEqualTo(20);
        assertThat(first.path("totalElements").asLong()).isEqualTo(23);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(ids(first)).containsExactlyElementsOf(ids.subList(0, 20));
        JsonNode second = page("/api/v1/studio/projects?page=1&size=20", owner);
        assertThat(ids(second)).containsExactlyElementsOf(ids.subList(20, 23));
        assertThat(second.path("totalElements").asLong()).isEqualTo(23);
        assertThat(page("/api/v1/studio/projects?page=2&size=20", owner).path("items")).isEmpty();
        assertThat(page("/api/v1/studio/projects?size=100", owner).path("items").size()).isEqualTo(23);
    }

    @Test
    void allChildListsUseDatabasePagesAndKeepTheirExistingRowFields() throws Exception {
        Project project = project(UUID.randomUUID().toString());
        for (int index = 0; index < 23; index++) history(project, index);
        String root = "/api/v1/studio/projects/" + project.getId();
        for (String area : List.of("builds", "review-requests", "release-requests")) {
            JsonNode first = page(root + "/" + area + "?size=10", project.getOwnerSubject());
            JsonNode second = page(root + "/" + area + "?page=1&size=10", project.getOwnerSubject());
            JsonNode last = page(root + "/" + area + "?page=2&size=10", project.getOwnerSubject());
            assertThat(first.path("items").size()).as(area).isEqualTo(10);
            assertThat(first.path("totalElements").asLong()).as(area).isEqualTo(23);
            assertThat(first.path("totalPages").asInt()).as(area).isEqualTo(3);
            assertThat(ids(first)).doesNotContainAnyElementsOf(ids(second));
            assertThat(ids(first).getLast()).isGreaterThan(ids(second).getFirst());
            assertThat(last.path("items").size()).as(area).isEqualTo(3);
            assertThat(page(root + "/" + area + "?page=3&size=10", project.getOwnerSubject()).path("totalElements").asLong()).isEqualTo(23);
        }
        assertThat(page(root + "/builds", project.getOwnerSubject()).path("items").get(0).path("checksum").asText()).isEqualTo("a".repeat(64));
        assertThat(page(root + "/review-requests", project.getOwnerSubject()).path("items").get(0).path("snapshot").asText()).contains(project.getProductCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=no", "size=1.5", "page=2147483647&size=100"})
    void invalidPagingIsRejectedRatherThanSilentlyClampedOrOverflowing(String query) {
        Project project = project(UUID.randomUUID().toString());
        for (String path : paths(project)) {
            assertThat(get(path + "?" + query, project.getOwnerSubject()).getStatusCode().value()).as(path + "?" + query).isEqualTo(400);
        }
    }

    @Test
    void emptyAndForeignCollectionsDoNotDiscloseAnotherOwnersRowsOrTotals() {
        Project project = project(UUID.randomUUID().toString());
        String other = "other-" + project.getOwnerSubject();
        JsonNode empty = page("/api/v1/studio/projects", other);
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("totalElements").asLong()).isZero();
        assertThat(empty.path("totalPages").asInt()).isZero();
        for (String path : paths(project).subList(1, 4)) {
            assertThat(get(path, other).getStatusCode().value()).isEqualTo(403);
            JsonNode ownedEmpty = page(path, project.getOwnerSubject());
            assertThat(ownedEmpty.path("items")).isEmpty();
            assertThat(ownedEmpty.path("totalElements").asLong()).isZero();
        }
    }

    @Test
    void activeRequestsRemainVisibleAcrossDraftVersionsWithoutPerProjectQueries() throws Exception {
        String owner = UUID.randomUUID().toString();
        for (int index = 0; index < 5; index++) {
            Project project = project(owner);
            ProjectReviewRequest approved = history(project, 0);
            ProjectReleaseRequest activeRelease = releases.saveAndFlush(ProjectReleaseRequest.create(project.getId(), approved.getId(), approved.getContentVersion()));
            ProjectSnapshot previous = mapper.readValue(approved.getSnapshot(), ProjectSnapshot.class);
            ProjectSnapshot next = new ProjectSnapshot(previous.projectId(), previous.productCode(), previous.ownerSubject(), 2,
                    previous.title(), previous.shortDescription(), previous.thumbnailUrl(), previous.videoUrl(), previous.content(), previous.price(),
                    previous.currency(), previous.buildId(), previous.version(), previous.fileSize(), previous.checksum(), previous.storagePath());
            ProjectReviewRequest activeReview = reviews.saveAndFlush(ProjectReviewRequest.create(project.getId(), 2, approved.getBuildId(), mapper.writeValueAsString(next)));
            project.linkReviewRequest(activeReview.getId());
            project.linkReleaseRequest(activeRelease.getId());
            project.update(new StorePage("New draft", "Summary", "https://example.com/cover.png", null, "New content", 2000), project.getContentVersion());
            project.update(new StorePage("Even newer draft", "Summary", "https://example.com/cover.png", null, "Newer content", 3000), project.getContentVersion());
            projects.saveAndFlush(project);
        }
        Statistics statistics = entities.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        JsonNode response;
        long queries;
        try {
            response = page("/api/v1/studio/projects?size=5", owner);
            queries = statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
        assertThat(response.path("items").size()).isEqualTo(5);
        assertThat(queries).as("one page, one count and two bulk request reads").isPositive().isLessThanOrEqualTo(4);
        for (JsonNode item : response.path("items")) {
            assertThat(item.path("currentReviewRequest").isNull()).isTrue();
            assertThat(item.path("currentReleaseRequest").isNull()).isTrue();
            assertThat(item.path("activeReviewRequest").path("status").asText()).isEqualTo("REQUESTED");
            assertThat(item.path("activeReleaseRequest").path("status").asText()).isEqualTo("REQUESTED");
            assertThat(item.path("releaseEligible").asBoolean()).isFalse();
        }
    }

    private Project project(String owner) { return projects.saveAndFlush(Project.create("PAGE-" + UUID.randomUUID(), "Game", owner)); }

    private ProjectReviewRequest history(Project project, int index) throws Exception {
        Build build = builds.saveAndFlush(Build.create(project.getId(), "build-" + index, "game.zip", "projects/" + project.getId() + "/build-" + index + ".zip", 1, "a".repeat(64)));
        ProjectSnapshot snapshot = new ProjectSnapshot(project.getId(), project.getProductCode(), project.getOwnerSubject(), index + 1L,
                "Game", "Summary", "https://example.com/cover.png", null, "Game content", 1000, "KRW", build.getId(), build.getVersion(), build.getFileSize(), build.getChecksum(), build.getStoragePath());
        ProjectReviewRequest review = ProjectReviewRequest.create(project.getId(), index + 1L, build.getId(), mapper.writeValueAsString(snapshot));
        review.recordResult(true, null, "reviewer");
        review = reviews.saveAndFlush(review);
        ProjectReleaseRequest release = ProjectReleaseRequest.create(project.getId(), review.getId(), index + 1L);
        release.recordResult(true, null);
        releases.saveAndFlush(release);
        return review;
    }

    private List<String> paths(Project project) {
        String root = "/api/v1/studio/projects/" + project.getId();
        return List.of("/api/v1/studio/projects", root + "/builds", root + "/review-requests", root + "/release-requests");
    }

    private ResponseEntity<JsonNode> get(String path, String owner) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(owner);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private JsonNode page(String path, String owner) {
        ResponseEntity<JsonNode> response = get(path, owner);
        assertThat(response.getStatusCode().value()).as(path).isEqualTo(200);
        JsonNode data = response.getBody().path("data");
        assertThat(data.path("items").isArray()).as(path + " must return a page, not an unbounded array").isTrue();
        return data;
    }

    private List<Long> ids(JsonNode page) {
        List<Long> result = new ArrayList<>();
        for (JsonNode item : page.path("items")) result.add(item.path("id").asLong());
        return result;
    }
}
