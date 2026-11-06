package com.stove.catalog.api.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.catalog.CatalogFixtures;
import com.stove.catalog.api.listener.ReleaseSubmissionMapper;
import com.stove.catalog.config.SecurityConfig;
import com.stove.catalog.core.domain.CatalogPublicationJob;
import com.stove.catalog.core.service.ReadPublicationJobs;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.security.CommonSecurityAutoConfiguration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PublicationJobController.class)
@ContextConfiguration(classes = {PublicationJobController.class, SecurityConfig.class, CommonSecurityAutoConfiguration.class})
class PublicationJobAccessTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean ReadPublicationJobs read;

    @BeforeEach
    void tokens() {
        for (String role : List.of("ADMIN", "CREATOR", "REVIEWER", "MEMBER"))
            when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg", "RS256")
                    .subject("subject").claim("roles", List.of(role)).build());
    }

    @Test
    void administratorSeesRetryMetadataWithoutStorageOrTheFullSubmission() throws Exception {
        ProjectReleaseRequestedEvent event = CatalogFixtures.event(130, 1);
        CatalogPublicationJob job = CatalogPublicationJob.accept(
                ReleaseSubmissionMapper.from(CatalogFixtures.envelope(event), event), CatalogFixtures.json(event.snapshot()), null);
        when(read.list(0, 20)).thenReturn(List.of(job));
        mvc.perform(get("/api/v1/admin/catalog-release-jobs").header("Authorization", "Bearer ADMIN"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data[0].state").value("PENDING"))
                .andExpect(jsonPath("$.data[0].snapshot").doesNotExist())
                .andExpect(jsonPath("$.data[0].storagePath").doesNotExist());
    }

    @Test
    void anonymousAndOtherRolesCannotInspectJobs() throws Exception {
        mvc.perform(get("/api/v1/admin/catalog-release-jobs")).andExpect(status().isUnauthorized());
        for (String role : List.of("CREATOR", "REVIEWER", "MEMBER"))
            mvc.perform(get("/api/v1/admin/catalog-release-jobs/10").header("Authorization", "Bearer " + role))
                    .andExpect(status().isForbidden());
        verifyNoInteractions(read);
    }

    @Test
    void prometheusReadsPassSecurityWithoutOpeningWritesOrOtherActuatorPaths() throws Exception {
        // No Actuator handler exists in this MVC slice; 404 checks only security.
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
        mvc.perform(post("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus/extra")).andExpect(status().isUnauthorized());
    }

}
