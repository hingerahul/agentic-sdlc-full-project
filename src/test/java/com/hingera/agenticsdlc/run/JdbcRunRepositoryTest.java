package com.hingera.agenticsdlc.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hingera.agenticsdlc.orchestration.GovernancePolicyEngine;
import com.hingera.agenticsdlc.orchestration.LocalPlanningStageWorker;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcRunRepositoryTest {
    @Test
    void detectsDeletedAuditTail() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:run-repository-tail;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        JdbcRunRepository repository = new JdbcRunRepository(jdbcTemplate,
                new ObjectMapper().findAndRegisterModules(), "integration-test-audit-key-with-32-bytes");
        repository.initializeSchema();
        RunService service = new RunService(new LocalPlanningStageWorker(),
                new GovernancePolicyEngine(), repository);
        service.create(new CreateRunRequest("Build a workflow.", ProjectScenario.GREENFIELD, "",
                List.of("Produces a review artifact")));

        jdbcTemplate.update("DELETE FROM sdlc_run_events WHERE sequence_id = "
                + "(SELECT MAX(sequence_id) FROM sdlc_run_events)");

        assertThrows(IllegalStateException.class, repository::loadAll);
    }

    @Test
    void restoresRunStageApprovalsAndAuditEvents() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:run-repository;DB_CLOSE_DELAY=-1");
        JdbcRunRepository repository = new JdbcRunRepository(new JdbcTemplate(dataSource),
                new ObjectMapper().findAndRegisterModules(), "integration-test-audit-key-with-32-bytes");
        repository.initializeSchema();

        RunService firstInstance = new RunService(new LocalPlanningStageWorker(),
                new GovernancePolicyEngine(), repository);
        EngineeringRun planned = firstInstance.create(new CreateRunRequest(
                "Build a workflow with measurable acceptance criteria.", ProjectScenario.GREENFIELD, "",
                List.of("Produces a review artifact")));
        firstInstance.execute(planned.id());
        firstInstance.review(planned.id(), new ReviewRequest(ReviewDecision.APPROVE, "Design approved"));

        RunService restartedInstance = new RunService(new LocalPlanningStageWorker(),
                new GovernancePolicyEngine(), repository);
        EngineeringRun restored = restartedInstance.findById(planned.id());

        assertEquals(RunStatus.REVIEW_REQUIRED, restored.status());
        assertEquals("WAITING_APPROVAL", restartedInstance.orchestrationSnapshot(planned.id()).traces().get(3).status());
        assertTrue(restartedInstance.auditTrail(planned.id()).stream()
                .anyMatch(event -> event.eventType().equals("HUMAN_REVIEW")));
        assertEquals("implementation", restartedInstance.auditTrail(planned.id()).stream()
                .filter(event -> event.eventType().equals("APPROVAL_REQUIRED"))
                .reduce((first, second) -> second).orElseThrow().stageId());
        new JdbcTemplate(dataSource).update("UPDATE sdlc_run_events SET event_json = '{}' WHERE sequence_id = 1");
        assertThrows(IllegalStateException.class, repository::loadAll);
    }
}
