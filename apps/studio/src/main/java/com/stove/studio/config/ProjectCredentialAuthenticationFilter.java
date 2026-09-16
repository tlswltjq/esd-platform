package com.stove.studio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.core.error.BusinessException;
import com.stove.studio.core.domain.ProjectCredential;
import com.stove.studio.core.service.ProjectCredentialService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class ProjectCredentialAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Project-Credential";
    private final ProjectCredentialService credentialService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/studio/ci/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            ProjectCredential credential = credentialService.authenticate(token);
            ProjectCredentialPrincipal principal = new ProjectCredentialPrincipal(
                    credential.getId(), credential.getGameId(), credential.getWorkspaceId());
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_CI"))));
            filterChain.doFilter(request, response);
        } catch (BusinessException rejected) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(),
                    java.util.Map.of("code", "UNAUTHORIZED", "message", rejected.getMessage()));
        }
    }
}
