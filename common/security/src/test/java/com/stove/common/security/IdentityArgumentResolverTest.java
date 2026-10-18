package com.stove.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.common.web.GlobalExceptionHandler;
import com.stove.common.web.SecurityExceptionHandler;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class IdentityArgumentResolverTest {

    private final IdentityController controller = new IdentityController();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new CurrentIdentityArgumentResolver())
            .setControllerAdvice(new SecurityExceptionHandler(), new GlobalExceptionHandler())
            .build();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesValidatedIdentities() throws Exception {
        authenticate(Jwt.withTokenValue("test").header("alg", "RS256")
                .subject("usr_creator").claim("member_id", 42L).build());
        mvc.perform(get("/identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(42))
                .andExpect(jsonPath("$.subject").value("usr_creator"));
        assertThat(controller.invocations).isEqualTo(1);
    }

    @Test
    void rejectsMalformedIdsBeforeControllerExecution() throws Exception {
        for (Object invalid : new Object[]{0L, -1L, 1.5, "42", new BigInteger("18446744073709551617")}) {
            authenticate(Jwt.withTokenValue("test").header("alg", "RS256")
                    .subject("usr_creator").claim("member_id", invalid).build());
            mvc.perform(get("/identity"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }
        assertThat(controller.invocations).isZero();
    }

    @Test
    void rejectsMissingMemberIdAndSubject() throws Exception {
        authenticate(Jwt.withTokenValue("test").header("alg", "RS256").subject("usr_creator").build());
        mvc.perform(get("/identity")).andExpect(status().isForbidden());
        authenticate(Jwt.withTokenValue("test").header("alg", "RS256").claim("member_id", 42L).build());
        mvc.perform(get("/identity")).andExpect(status().isForbidden());
        assertThat(controller.invocations).isZero();
    }

    @Test
    void rejectsAbsentOrIncompleteAuthentication() throws Exception {
        mvc.perform(get("/identity")).andExpect(status().isForbidden());
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256")
                .subject("usr_test").claim("member_id", 42L).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc.perform(get("/identity")).andExpect(status().isForbidden());
        assertThat(controller.invocations).isZero();
    }

    @Test
    void rejectsAnAuthenticatedNonJwtPrincipal() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "usr_test", "ignored", List.of()));
        mvc.perform(get("/identity")).andExpect(status().isForbidden());
        assertThat(controller.invocations).isZero();
    }

    private void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @RestController
    static class IdentityController {
        int invocations;

        @GetMapping("/identity")
        Map<String, Object> identity(@CurrentMemberId Long memberId, @CurrentSubject String subject) {
            invocations++;
            return Map.of("memberId", memberId, "subject", subject);
        }
    }
}
