package com.stove.studio.api.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.stove.common.security.CommonSecurityAutoConfiguration;
import com.stove.studio.config.RequestJsonConfig;
import com.stove.studio.config.SecurityConfig;
import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.service.project.*;
import com.stove.studio.core.service.release.RequestProjectRelease;
import com.stove.studio.core.service.review.RequestProjectReview;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

@WebMvcTest(ProjectController.class)
@ContextConfiguration(
        classes = {
            ProjectController.class,
            SecurityConfig.class,
            CommonSecurityAutoConfiguration.class,
            RequestJsonConfig.class
        })
class ProjectAccessTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean CreateProject create;
    @MockitoBean UpdateStorePage update;
    @MockitoBean ReadProjects read;
    @MockitoBean RequestProjectReview review;
    @MockitoBean RequestProjectRelease publish;

    @BeforeEach
    void tokens() {
        when(decoder.decode("creator"))
                .thenReturn(
                        Jwt.withTokenValue("creator")
                                .header("alg", "RS256")
                                .subject("owner")
                                .claim("roles", List.of("CREATOR"))
                                .build());
        when(decoder.decode("member"))
                .thenReturn(
                        Jwt.withTokenValue("member")
                                .header("alg", "RS256")
                                .subject("buyer")
                                .claim("roles", List.of("MEMBER"))
                                .build());
    }

    @Test
    void onlyCreatorCanCreateProjectUsingAuthenticatedSubject() throws Exception {
        when(create.execute("owner", "GAME", "Game"))
                .thenReturn(Project.create("GAME", "Game", "owner"));
        mvc.perform(
                        post("/api/v1/studio/projects")
                                .header("Authorization", "Bearer creator")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"productCode\":\"GAME\",\"title\":\"Game\"}"))
                .andExpect(status().isCreated());
        verify(create).execute("owner", "GAME", "Game");
    }

    @Test
    void anonymousAndMemberRequestsAreDeniedBeforeBusinessExecution() throws Exception {
        mvc.perform(get("/api/v1/studio/projects")).andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/studio/projects")
                                .header("Authorization", "Bearer member")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"productCode\":\"GAME\",\"title\":\"Game\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/studio/projects/1/review-requests")
                                .header("Authorization", "Bearer member"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/studio/projects/1/release-requests")
                                .header("Authorization", "Bearer member"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(create, review, publish);
    }

    @Test
    void fractionalPriceIsRejectedInsteadOfSilentlyTruncated() throws Exception {
        mvc.perform(
                        put("/api/v1/studio/projects/1/store-page")
                                .header("Authorization", "Bearer creator")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"contentVersion\":1,\"title\":\"Game\",\"shortDescription\":\"Summary\",\"content\":\"Content\",\"price\":1000.5}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(update);
    }
}
