package com.stove.review.api.controller;

import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.common.security.CommonSecurityAutoConfiguration;
import com.stove.review.ReviewFixtures;
import com.stove.review.config.RequestJsonConfig;
import com.stove.review.config.SecurityConfig;
import com.stove.review.core.domain.ProjectReview;
import com.stove.review.core.domain.ReviewStatus;
import com.stove.review.core.service.DecideProjectReview;
import com.stove.review.core.service.ReadProjectReviews;
import com.stove.review.core.service.ReadReviewBuildDownload;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReviewController.class)
@ContextConfiguration(
        classes = {
            ReviewController.class,
            SecurityConfig.class,
            RequestJsonConfig.class,
            CommonSecurityAutoConfiguration.class
        })
class ReviewAccessTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean ReadProjectReviews read;
    @MockitoBean ReadReviewBuildDownload download;
    @MockitoBean DecideProjectReview decide;

    @BeforeEach
    void tokens() {
        when(decoder.decode(anyString()))
                .thenAnswer(
                        call -> {
                            String role = call.getArgument(0);
                            return Jwt.withTokenValue(role)
                                    .header("alg", "RS256")
                                    .subject("subject-" + role)
                                    .claim("roles", List.of(role.toUpperCase()))
                                    .build();
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"reviewer", "admin"})
    void authorizedDecisionUsesTheJwtSubject(String role) throws Exception {
        ProjectReview r = ReviewFixtures.review(1);
        r.decide(ReviewStatus.APPROVED, "subject-" + role, null);
        when(decide.execute(1L, "subject-" + role, ReviewStatus.APPROVED, null)).thenReturn(r);
        when(read.snapshot(r)).thenReturn(ReviewFixtures.event(1, 1).snapshot());
        mvc.perform(
                        post("/api/v1/reviews/1/approve")
                                .header("Authorization", "Bearer " + role)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"actor\":\"spoofed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actor").value("subject-" + role))
                .andExpect(jsonPath("$.data.snapshot.storagePath").doesNotExist());
        verify(decide).execute(1L, "subject-" + role, ReviewStatus.APPROVED, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "creator"})
    void nonReviewRolesCannotReadDownloadOrDecide(String role) throws Exception {
        for (String path : new String[] {"", "/1", "/1/build-download"})
            mvc.perform(get("/api/v1/reviews" + path).header("Authorization", "Bearer " + role))
                    .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/reviews/1/approve").header("Authorization", "Bearer " + role))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/reviews/1/reject")
                                .header("Authorization", "Bearer " + role)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"feedback\":\"Fix\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(read, download, decide);
    }

    @Test
    void anonymousCannotRead() throws Exception {
        mvc.perform(get("/api/v1/reviews")).andExpect(status().isUnauthorized());
        verifyNoInteractions(read);
    }

    @Test
    void rejectionCannotSupplyAnActorOrOtherDecisionFields() throws Exception {
        mvc.perform(
                        post("/api/v1/reviews/1/reject")
                                .header("Authorization", "Bearer reviewer")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"feedback\":\"Fix\",\"actor\":\"spoofed\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(decide);
    }

    @Test
    void blankOrTooLongFeedbackIsRejected() throws Exception {
        for (String feedback : new String[] {" ", "x".repeat(2001)})
            mvc.perform(
                            post("/api/v1/reviews/1/reject")
                                    .header("Authorization", "Bearer reviewer")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            ReviewFixtures.json(
                                                    Map.of("feedback", feedback))))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(decide);
    }
}
