package com.hingera.agenticsdlc.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;

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
    private static final Logger log = LoggerFactory.getLogger(ApiSecurityTest.class);

    @Autowired private MockMvc mockMvc;

    @Test
    void requiresAuthenticationForApiReads() throws Exception {
        var response = mockMvc.perform(get("/api/runs"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse();
        org.junit.jupiter.api.Assertions.assertTrue(response.getHeader("WWW-Authenticate").startsWith("Basic"),
                "Unauthenticated API requests should trigger the browser's Basic Auth prompt");
    }

    @Test
    void servesOpenApiWithBasicAuthAndExposesSwaggerAuthorizeFlow() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Agentic SDLC API"))
                .andExpect(jsonPath("$.security[0].basicAuth").isArray())
                .andExpect(jsonPath("$.components.securitySchemes.basicAuth.scheme").value("basic"));

        mockMvc.perform(get("/v3/api-docs")
                        .with(httpBasic("test-requester", "test-requester-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/runs'].get").exists())
                .andExpect(jsonPath("$.paths['/api/runs'].post").exists())
                .andExpect(jsonPath("$.paths['/api/runs/{id}/execute'].post").exists())
                .andExpect(jsonPath("$.paths['/api/runs/{id}/review'].post").exists())
                .andExpect(jsonPath("$.paths['/api/runs/{id}/stages/{stageId}/output'].patch").exists());

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/swagger-ui/swagger-initializer.js"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(containsString("requestInterceptor")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(containsString("XSRF-TOKEN")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(containsString("X-XSRF-TOKEN")));

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

    @Test
    void exercisesEveryRunEndpointWithSampleData() throws Exception {
        mockMvc.perform(get("/api/csrf")
                        .with(httpBasic("test-requester", "test-requester-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").isNotEmpty())
                .andExpect(jsonPath("$.token").isNotEmpty());

        String created = mockMvc.perform(post("/api/runs")
                        .with(httpBasic("test-requester", "test-requester-password"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "requirement": "Update an existing inventory service safely.",
                                  "scenario": "BROWNFIELD",
                                  "codebaseContext": "inventory module; item API; stock persistence",
                                  "acceptanceCriteria": [
                                    "Updating an item's stock persists the new quantity"
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assessment.scenario").value("BROWNFIELD"))
                .andReturn().getResponse().getContentAsString();
        String runId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(created).get("id").asText();

        mockMvc.perform(get("/api/runs")
                        .with(httpBasic("test-requester", "test-requester-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem(runId)));

        mockMvc.perform(get("/api/runs/{id}", runId)
                        .with(httpBasic("test-requester", "test-requester-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId));

        mockMvc.perform(get("/api/runs/{id}/orchestration", runId)
                        .with(httpBasic("test-requester", "test-requester-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graph.length()").value(7));

        mockMvc.perform(get("/api/runs/{id}/events", runId)
                        .with(httpBasic("test-requester", "test-requester-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("RUN_CREATED"));

        mockMvc.perform(post("/api/runs/{id}/execute", runId)
                        .with(httpBasic("test-requester", "test-requester-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));

        mockMvc.perform(post("/api/runs/{id}/review", runId)
                        .with(httpBasic("test-reviewer", "test-reviewer-password"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"decision":"REQUEST_CHANGES","feedback":"Add inventory quantity limits."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHANGES_REQUESTED"));

        mockMvc.perform(post("/api/runs/{id}/replan", runId)
                        .with(httpBasic("test-requester", "test-requester-password"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "requirement": "Update an existing inventory service with quantity limits.",
                                  "scenario": "BROWNFIELD",
                                  "codebaseContext": "inventory module; item API; stock persistence",
                                  "acceptanceCriteria": [
                                    "Updating an item's stock persists the new quantity",
                                    "Stock cannot be updated below zero"
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.assessment.acceptanceCriteria.length()").value(2));

        mockMvc.perform(post("/api/runs/{id}/execute", runId)
                        .with(httpBasic("test-requester", "test-requester-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));

        mockMvc.perform(patch("/api/runs/{id}/stages/{stageId}/output", runId, "codebase-analysis")
                        .with(httpBasic("test-requester", "test-requester-password"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"output\":\"Revised inventory API and stock persistence impact analysis.\","
                                + "\"rationale\":\"Reviewer supplied a corrected upstream dependency.\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/runs/{id}/stages/{stageId}/output", runId, "codebase-analysis")
                        .with(httpBasic("test-reviewer", "test-reviewer-password"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"output\":\"Revised inventory API and stock persistence impact analysis.\","
                                + "\"rationale\":\"Reviewer supplied a corrected upstream dependency.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));

        mockMvc.perform(post("/api/runs/{id}/review", runId)
                        .with(httpBasic("test-reviewer", "test-reviewer-password"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\",\"feedback\":\"Revised architecture reviewed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));
        mockMvc.perform(post("/api/runs/{id}/review", runId)
                        .with(httpBasic("test-reviewer", "test-reviewer-password"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\",\"feedback\":\"Revised implementation reviewed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value(containsString(
                        "Revised inventory API and stock persistence impact analysis")));

        mockMvc.perform(get("/api/runs/{id}/events", runId)
                        .with(httpBasic("test-requester", "test-requester-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].eventType", hasItem("RUN_REPLANNED")))
                .andExpect(jsonPath("$[*].eventType", hasItem("UPSTREAM_OUTPUT_REVISED")))
                .andExpect(jsonPath("$[*].actor", hasItem("test-reviewer")));
    }

    @Test
    void completesGreenfieldWorkflowThroughAuthenticatedApi() throws Exception {
        log.info("Starting greenfield API integration scenario with equipment-loan sample data");
        String created = mockMvc.perform(post("/api/runs")
                        .with(httpBasic("test-requester", "test-requester-password"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "requirement": "Create an equipment loan service with measurable outcomes.",
                                  "scenario": "GREENFIELD",
                                  "acceptanceCriteria": [
                                    "A borrower can check out an available item",
                                    "A returned item becomes available"
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.assessment.clarity").value("CLEAR"))
                .andReturn().getResponse().getContentAsString();
        String runId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(created).get("id").asText();
        log.info("Created run {}; intake status=PLANNED, clarity=CLEAR", runId);

        mockMvc.perform(post("/api/runs/{id}/execute", runId)
                        .with(httpBasic("test-requester", "test-requester-password"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));
        log.info("Executed run {}; stopped at architecture approval checkpoint", runId);

        mockMvc.perform(post("/api/runs/{id}/review", runId)
                        .with(httpBasic("test-reviewer", "test-reviewer-password"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\",\"feedback\":\"Architecture reviewed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));
        log.info("Reviewer approved architecture for run {}; implementation checkpoint is pending", runId);

        mockMvc.perform(post("/api/runs/{id}/review", runId)
                        .with(httpBasic("test-reviewer", "test-reviewer-password"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\",\"feedback\":\"Implementation plan reviewed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.validation.passed").value(true))
                .andExpect(jsonPath("$.outcome").value(containsString(
                        "A borrower can check out an available item")))
                .andExpect(jsonPath("$.outcome").value(containsString(
                        "A returned item becomes available")));
        log.info("Reviewer approved implementation plan for run {}; structural validation passed", runId);

        mockMvc.perform(get("/api/runs/{id}/orchestration", runId)
                        .with(httpBasic("test-requester", "test-requester-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionWaves[4]").isArray());
        log.info("Verified orchestration waves for run {}; testing and documentation are parallel-ready", runId);

        mockMvc.perform(post("/api/runs/{id}/review", runId)
                        .with(httpBasic("test-reviewer", "test-reviewer-password"))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\",\"feedback\":\"Release evidence reviewed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        log.info("Reviewer approved release readiness for run {}; final status=APPROVED", runId);

        mockMvc.perform(get("/api/runs/{id}/events", runId)
                        .with(httpBasic("test-requester", "test-requester-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actor").value("test-requester"))
                .andExpect(jsonPath("$[?(@.actor == 'test-reviewer')].actor")
                        .value(hasItem("test-reviewer")));
        log.info("Verified requester and reviewer identities in audit events for run {}", runId);
    }
}
