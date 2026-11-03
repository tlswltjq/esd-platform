package com.stove.studio.api.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.common.security.CommonSecurityAutoConfiguration;
import com.stove.studio.config.RequestJsonConfig;
import com.stove.studio.config.SecurityConfig;
import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.domain.project.ProjectRequestCommand;
import com.stove.studio.core.domain.project.ProjectRequestKind;
import com.stove.studio.core.domain.project.WithdrawalCommand;
import com.stove.studio.core.service.project.RequestProjectWithdrawal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProjectWithdrawalController.class)
@ContextConfiguration(classes = {ProjectWithdrawalController.class, SecurityConfig.class,
        CommonSecurityAutoConfiguration.class, RequestJsonConfig.class})
class ProjectWithdrawalAccessTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean RequestProjectWithdrawal withdraw;

    @BeforeEach
    void tokens() {
        for (String role : List.of("CREATOR", "MEMBER", "ADMIN")) {
            when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg", "RS256")
                    .subject("owner").claim("roles", List.of(role)).build());
        }
    }

    @Test
    void optionalReasonUsesJwtSubjectAndReturnsAnUncachedAcceptance() throws Exception {
        WithdrawalCommand input = new WithdrawalCommand("withdraw-1", null);
        when(withdraw.review(1L, 10L, "owner", input)).thenReturn(receipt());
        mvc.perform(post("/api/v1/studio/projects/1/review-requests/10/withdrawals")
                        .header("Authorization", "Bearer CREATOR").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"withdraw-1\",\"ownerSubject\":\"spoofed\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.state").value("PENDING"))
                .andExpect(jsonPath("$.data.subject").value("owner"));
        verify(withdraw).review(1L, 10L, "owner", input);
    }

    @Test
    void receiptLookupKeepsItsExplicitTargetAndRole() throws Exception {
        when(withdraw.receipt(1L, 10L, 20L, "owner", ProjectRequestKind.WITHDRAW_REVIEW)).thenReturn(receipt());
        mvc.perform(get("/api/v1/studio/projects/1/review-requests/10/withdrawals/20")
                        .header("Authorization", "Bearer CREATOR"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(withdraw).receipt(1L, 10L, 20L, "owner", ProjectRequestKind.WITHDRAW_REVIEW);
    }

    @Test
    void anonymousAndNonCreatorCannotReadOrWithdrawAndOversizedReasonIsRejected() throws Exception {
        String path = "/api/v1/studio/projects/1/release-requests/10/withdrawals";
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        for (String role : List.of("MEMBER", "ADMIN")) {
            mvc.perform(post(path).header("Authorization", "Bearer " + role))
                    .andExpect(status().isForbidden());
            mvc.perform(get(path + "/20").header("Authorization", "Bearer " + role))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post(path).header("Authorization", "Bearer CREATOR").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"key\",\"reason\":\"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(withdraw);
    }

    private ProjectRequestCommand receipt() {
        Project project = Project.create("GAME", "Game", "owner");
        ReflectionTestUtils.setField(project, "id", 1L);
        ProjectRequestCommand command = ProjectRequestCommand.withdrawal(project, ProjectRequestKind.WITHDRAW_REVIEW,
                new WithdrawalCommand("withdraw-1", null), 10L, 1);
        ReflectionTestUtils.setField(command, "id", 20L);
        return command;
    }
}
