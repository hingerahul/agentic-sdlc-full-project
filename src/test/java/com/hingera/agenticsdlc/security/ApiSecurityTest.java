package com.hingera.agenticsdlc.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:security-api;DB_CLOSE_DELAY=-1",
        "agentic.security.requester.username=test-requester",
        "agentic.security.requester.password=test-requester-password",
        "agentic.security.reviewer.username=test-reviewer",
        "agentic.security.reviewer.password=test-reviewer-password",
        "agentic.audit.key=security-test-audit-key-with-more-than-32-bytes"
})
@AutoConfigureMockMvc
class ApiSecurityTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void requiresAuthenticationForApiReads() throws Exception {
        mockMvc.perform(get("/api/runs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reviewerRoleIsRequiredForHumanApproval() throws Exception {
        String runId = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/runs/{id}/review", runId)
                        .with(httpBasic("test-requester", "test-requester-password"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\",\"feedback\":\"Approved\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/runs/{id}/review", runId)
                        .with(httpBasic("test-reviewer", "test-reviewer-password"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\",\"feedback\":\"Approved\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void auditEventsRecordAuthenticatedRequester() throws Exception {
        String created = mockMvc.perform(post("/api/runs")
                        .with(httpBasic("test-requester", "test-requester-password"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "requirement": "Create a reviewable planning run with criteria.",
                                  "scenario": "GREENFIELD",
                                  "acceptanceCriteria": ["A review artifact is created"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String runId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(created).get("id").asText();

        mockMvc.perform(get("/api/runs/{id}/events", runId)
                        .with(httpBasic("test-requester", "test-requester-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actor").value("test-requester"));
    }
}
