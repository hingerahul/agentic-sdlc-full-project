# Agentic SDLC Full Project

A Java 21 / Spring Boot REST API prototype for bounded requirement intake, deterministic triage, a reviewable plan, structural checks, and human review. It does **not** implement code autonomously or qualify as a production-grade SDLC orchestration platform.

## Implemented scope

- Requirement intake with 20–10,000 character validation.
- Optional scenario (`AUTO`, `GREENFIELD`, `BROWNFIELD`), supplied codebase context, and acceptance criteria.
- Deterministic heuristic triage that flags selected vague terms, missing acceptance criteria, and missing brownfield context. It records questions and conservative assumptions; it is not an LLM or a complete semantic ambiguity detector.
- Fixed five-item plan, scenario-aware explanatory notes, structural validation, Markdown outcome, and explicit human review endpoint.
- A modeled orchestration graph and snapshot endpoint. The graph is **not** a graph-driven scheduler; parallel execution, synchronization, retries, rollback, persistence, dynamic replanning, and operational telemetry are not implemented.
- Brownfield reasoning only reflects context submitted by the caller. The application does not inspect repositories or infer impacted modules/services/data flows.

## Requirements and run

- JDK 21+
- Maven 3.9+

```bash
mvn spring-boot:run
mvn test
```

The API listens on `http://localhost:8080`. Runs are in-memory and are lost on restart.

## API

`POST /api/runs` accepts a requirement and optional scenario/context/criteria:

```json
{
  "requirement": "Refactor the existing billing API to fix invoice rounding.",
  "scenario": "BROWNFIELD",
  "codebaseContext": "billing module; invoice API; reconciliation data flow",
  "acceptanceCriteria": ["Invoice totals follow the documented currency rounding rule"]
}
```

`scenario` may be `AUTO`, `GREENFIELD`, or `BROWNFIELD`. In `AUTO`, a small keyword heuristic selects a scenario; provide an explicit value when classification matters. `GET /api/runs/{id}` returns the assessment, questions, acceptance criteria, plan, and outcome. `GET /api/runs/{id}/orchestration` returns the modeled graph snapshot. `POST /api/runs/{id}/execute` runs the deterministic local workflow; `POST /api/runs/{id}/review` records `APPROVE` or `REQUEST_CHANGES`. Approval is refused when the intake assessment has unresolved clarification questions. Review decisions are not authenticated or durably audited in this prototype.

## Scope coverage

| Requirement area | Current implementation | Not implemented / limitation |
|---|---|---|
| Requirement understanding | Heuristic scenario/ambiguity triage, questions, assumptions, criteria capture | No semantic reasoning or interactive clarification loop |
| Task decomposition | Fixed five-item plan with scenario-specific notes | No actionable task graph generated from requirements |
| Brownfield reasoning | Accepts and carries caller-provided context | No repository inspection, architecture discovery, or impact analysis |
| SDLC orchestration | Static graph model, status snapshot, explicit final review | No runtime graph scheduler, parallel branches, sync, dynamic replanning, retries, fallback, rollback, or durable lineage |
| Engineering outputs | Review package and proposed plan | No generated production code, API/schema artifacts, tests, or documentation changes for a target project |
| Validation/risk | Structural checks and explicit limitation statements | No acceptance execution, security/compliance scans, or policy engine |
| Controlled autonomy | No source mutation; explicit human review; ambiguous inputs cannot be approved | No identity/role authorization or durable audit record |
| Summary | Requirement, assessment, questions, assumptions, context, plan, validation, limitations | Metrics are modeled fields, not collected operational telemetry |

## Safety and quality boundary

The output is a planning/review artifact, not proof that a requirement has been implemented. `APPROVE` is a recorded user-submitted decision; the current prototype does not authenticate the reviewer. Structural validation does not establish functional correctness, security, regulatory compliance, or release readiness. Do not use this prototype to authorize production changes.
