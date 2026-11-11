# Agentic SDLC Full Project

Java 21 / Spring Boot REST API prototype for bounded requirement intake, deterministic triage, a dependency-aware local workflow, structural validation, and explicit human review. It is not a production-grade autonomous SDLC platform.

## Current implementation

- Requirement intake with optional `AUTO`, `GREENFIELD`, or `BROWNFIELD` scenario, caller-provided codebase context, and acceptance criteria.
- Heuristic ambiguity questions and approval blocking when intake has unresolved concerns.
- Explicit lifecycle graph: requirement analysis -> codebase-context analysis -> architecture/design -> implementation planning -> testing and documentation branches -> synchronized release readiness.
- The local runtime evaluates graph entry dependencies, records stage transitions, safe-stops on invalid graph/dependency gates, and requires human approval for release readiness.
- In-memory audit event endpoint: `GET /api/runs/{id}/events`.
- Human review endpoint: `POST /api/runs/{id}/review`.

## Important limitations

This is a deterministic demonstration, not production SDLC automation. Parallel branches are represented in the DAG but are evaluated sequentially, not dispatched concurrently. Run/audit state is in memory and disappears on restart. Audit actor labels are not authenticated. No retry/fallback/rollback, durable persistence, repository scanning, source mutation, code/test/schema generation, CI execution, security/compliance policy engine, or operational MTTR/reliability collection is implemented. Metrics are limited and must not be interpreted as measured production reliability.

## Running

Requires JDK 21+ and Maven 3.9+.

```bash
mvn spring-boot:run
mvn test
```

Submit a request:

```json
{
  "requirement": "Refactor the existing billing API to fix invoice rounding.",
  "scenario": "BROWNFIELD",
  "codebaseContext": "billing module; invoice API; reconciliation data flow",
  "acceptanceCriteria": ["Invoice totals follow the documented currency rounding rule"]
}
```

`scenario` is optional and defaults to `AUTO`. The output is a reviewable plan, not evidence that a code change was made. Do not use the prototype to authorize production changes.
