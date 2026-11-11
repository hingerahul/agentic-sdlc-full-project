# Agentic SDLC Full Project

Java 21 / Spring Boot REST API prototype for bounded requirement intake, deterministic triage, a dependency-aware local workflow, structural validation, and explicit human review. It is not a production-grade autonomous SDLC platform.

## Architecture overview

### Components

- **REST/API layer** (`RunController`, `CsrfController`): creates, executes, reviews, replans, and reports on runs.
- **Requirement understanding** (`RequirementUnderstandingService`): normalizes the requested scenario, detects a small set of ambiguity cues, and records supplied acceptance criteria and brownfield context. This is deterministic heuristic triage, not an LLM.
- **Run state machine** (`RunService`): owns lifecycle transitions, per-run synchronization, review checkpoints, retries/fallback, validation, metrics, and audit events.
- **Orchestration graph** (`RunOrchestrationService`): defines stage dependencies, entry/exit gates, topological waves, cycle checks, and graph-level risk constraints.
- **Stage worker and policy** (`StageWorker`, `LocalPlanningStageWorker`, `GovernancePolicyEngine`): runs bounded local planning notes and applies local capability/approval rules. The interface permits replacing the local worker; the prototype does not dispatch external agents.
- **Persistence and identity** (`JdbcRunRepository`, H2, Spring Security): restores run/workflow state on startup, stores ordered HMAC-chained events, authenticates requester/reviewer accounts, and records the authenticated audit actor.

### Orchestration model and control flow

`POST /api/runs` assesses the requirement, creates five reviewable work items, persists the run, and records `RUN_CREATED`. `POST /api/runs/{id}/execute` checks the DAG and advances ready stages in dependency waves. Each stage must pass its entry gate, local policy check, bounded worker attempts, and output exit gate. Prior stage outputs are passed to downstream workers; approval decisions remain in the audit lineage. Independent stages in a wave run concurrently.

The DAG is: `requirement-analysis` -> `codebase-analysis` -> `architecture-design` -> `implementation` -> (`testing` || `documentation`) -> `release-readiness`. Architecture/design and implementation each require reviewer approval before execution; release readiness requires a final reviewer approval after structural validation. A held approval pauses advancement; a reviewer decision resumes it. `POST /api/runs/{id}/replan` invalidates stage outputs and approvals, reassesses the revised input, and keeps prior decisions in the audit history. High-risk failures and failed release validation safe-stop; non-high-risk stages may use a non-mutating fallback after two attempts.

Key choices are intentionally conservative: no repository writes, network calls, arbitrary code execution, or deployment; high-impact stages require human approval; requesters cannot submit review decisions; and re-planning is explicit rather than automatically triggered. The orchestration endpoint exposes the graph, gates, waves, traces, and event-derived metrics. The events endpoint exposes the decision history.

## Scenario walkthroughs

All three scenarios use the same fixed DAG and review controls; scenario classification changes the intake assessment and context, not the graph topology. The worker outputs are planning notes, not generated implementation artifacts.

| Scenario | Example input and decomposition | Orchestration path | Validation and human control |
|---|---|---|---|
| **Greenfield** | `scenario: GREENFIELD`; requirement: “Create a service that tracks equipment loans.” Criteria: “A borrower can check out an available item” and “A returned item becomes available.” Decomposes into requirement/criteria analysis, design, implementation plan, testing plan, documentation plan, and release review. | Run requirement analysis; stop for architecture approval; prepare implementation plan; stop for implementation approval; execute testing and documentation branches in parallel; synchronize at release readiness. | Intake is clear when measurable criteria are supplied. Final checks confirm requirement presence, expected workflow items, completion states, and non-empty stage notes. Reviewer must approve each checkpoint. No service code or tests are generated or run. |
| **Brownfield** | `scenario: BROWNFIELD`; requirement: “Refactor the existing billing service to fix invoice rounding.” Supply context such as “billing module; invoice API; reconciliation data flow” and a criterion such as “Totals follow the currency rounding rule.” Decomposes around the supplied impacted module/API/data flow plus design, implementation, test, documentation, and release review. | Same gated DAG; the supplied context is available to downstream planning stages. The prototype does not inspect the repository to discover additional dependencies. | Intake requests context if absent. Final checks are structural/reviewability checks only; a reviewer must validate the claimed impact analysis and acceptance evidence against the actual codebase. |
| **Ambiguous** | `scenario: AUTO`; requirement: “Make the system better.” No criteria. AUTO classifies this as greenfield absent a brownfield cue, but “better” and missing criteria trigger clarification questions. Decomposition captures the ambiguity and the evidence/questions that must be resolved. | The run is held at a review checkpoint. Approval is rejected while clarification remains unresolved. Use `/replan` with a measurable requirement and criteria; prior decisions remain audited, but prior outputs/approvals are invalidated. | Intake reports `NEEDS_CLARIFICATION`, the vague-term reason, and questions for an observable outcome and verification evidence. Do not treat this as a validated plan; after re-planning, reassess and proceed only when the assessment is clear. |

## Current implementation

- Requirement intake with optional `AUTO`, `GREENFIELD`, or `BROWNFIELD` scenario, caller-provided codebase context, and acceptance criteria.
- Heuristic ambiguity questions and approval blocking when intake has unresolved concerns.
- Explicit lifecycle graph: requirement analysis -> codebase-context analysis -> architecture/design -> implementation planning -> testing and documentation branches -> synchronized release readiness.
- The runtime exposes entry/exit gates and topological execution waves. Independent local stages in a wave execute concurrently; testing and documentation synchronize at release readiness.
- Architecture/design and implementation each pause for explicit human approval before continuing; release readiness requires a final approval after structural validation.
- `POST /api/runs/{id}/replan` accepts a revised `CreateRunRequest`, invalidates prior stage states/approvals, restarts from requirement analysis, and preserves earlier decisions in the audit trail.
- Stage execution has a two-attempt bound, retries transient failures once, permits non-mutating fallback for non-high-risk planning stages, and safe-stops on high-risk failure or failed release validation. Safe-stop invalidates prior local planning outputs and records rollback events; it cannot reverse external changes because external actions are disabled.
- Policy decisions are audit events. The local policy requires human approval for high-risk stages and denies repository writes, network calls, code execution, and deployment.
- HTTP Basic authentication protects API endpoints. Requester credentials may create and execute runs; only the separately configured reviewer account may submit approval decisions. Audit actors come from the authenticated principal.
- The review API uses a CSRF cookie/token (`GET /api/csrf`) in addition to Basic authentication.
- H2-backed audit event endpoint: `GET /api/runs/{id}/events`.
- Human review endpoint: `POST /api/runs/{id}/review`.
- Orchestration snapshot endpoint: `GET /api/runs/{id}/orchestration` includes graph, gates, waves, stage traces, and runtime counters.

## Important limitations

This is a deterministic demonstration, not production SDLC automation. HTTP Basic uses two local users configured from environment-backed secrets; it is not SSO/enterprise identity or credential lifecycle management. The default H2 database is a local file at `./data/agentic-sdlc`; it persists requirements and audit events without database encryption, and must be protected by host-level access controls. Audit events use a keyed HMAC chain that detects modification/deletion while the audit key remains secret, but the store is not immutable or an external compliance ledger. Parallel stages are local planning workers, not dispatched agents or external jobs. Re-planning is an explicit human-triggered reset, not automatic change detection from upstream agent outputs. Policy enforcement is a local allowlist of disabled capabilities and approval gates, not an enterprise security/compliance policy engine. MTTR, retries, fallbacks, rollback, approval, failure, and latency values are derived from recorded workflow events and are not production reliability measurements. Repository scanning, source mutation, code/test/schema generation, and CI execution are not implemented.

## Running

Requires JDK 21+ and Maven 3.9+.

The local H2 database is created on first start. Configure a different JDBC URL with Spring's `spring.datasource.url` setting before deployment; do not expose this prototype or its H2 files to untrusted users.

Before starting the API, provide distinct requester and reviewer credentials through `AGENTIC_SDLC_REQUESTER_USERNAME`, `AGENTIC_SDLC_REQUESTER_PASSWORD`, `AGENTIC_SDLC_REVIEWER_USERNAME`, and `AGENTIC_SDLC_REVIEWER_PASSWORD`. Passwords must be at least 12 characters. Also set `AGENTIC_SDLC_AUDIT_KEY` to a secret of at least 32 bytes; changing it makes existing audit chains unverifiable. A copyable local-development template is in `.env.example`; copy it to `.env`, replace the example values, and source it in the terminal that launches the app. `.env` is gitignored. The example values are public placeholders and must never be used outside local development. Obtain a CSRF token from `GET /api/csrf` with the cookie jar enabled, then send the returned header name/token and cookie with each POST request.

```bash
cp .env.example .env
# Edit .env and replace all example values with your own local values.
set -a
. ./.env
set +a
mvn test
mvn spring-boot:run
```

### End-to-end API example

In another terminal, set the credential variables above (or otherwise make them available), then obtain a CSRF token and cookie. The API listens on port 8080 by default.

```bash
BASE=http://localhost:8080
COOKIE_JAR=$(mktemp)
CSRF_JSON=$(curl -fsS -u "$AGENTIC_SDLC_REQUESTER_USERNAME:$AGENTIC_SDLC_REQUESTER_PASSWORD" \
  -c "$COOKIE_JAR" "$BASE/api/csrf")
CSRF_TOKEN=$(printf '%s' "$CSRF_JSON" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
```

Create a greenfield run and capture its returned ID:

```bash
RUN_JSON=$(curl -fsS -u "$AGENTIC_SDLC_REQUESTER_USERNAME:$AGENTIC_SDLC_REQUESTER_PASSWORD" \
  -b "$COOKIE_JAR" -c "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -H "X-XSRF-TOKEN: $CSRF_TOKEN" -X POST "$BASE/api/runs" -d '{
    "requirement": "Create a service that tracks equipment loans.",
    "scenario": "GREENFIELD",
    "acceptanceCriteria": [
      "A borrower can check out an available item",
      "A returned item becomes available"
    ]
  }')
RUN_ID=$(printf '%s' "$RUN_JSON" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
printf 'Run ID: %s\n' "$RUN_ID"
```

Execute as requester, then approve each pending checkpoint as reviewer. After each decision, inspect the run response: architecture approval advances to the implementation checkpoint; implementation approval advances through testing/documentation to release readiness; the final approval marks the run approved.

```bash
curl -fsS -u "$AGENTIC_SDLC_REQUESTER_USERNAME:$AGENTIC_SDLC_REQUESTER_PASSWORD" \
  -b "$COOKIE_JAR" -c "$COOKIE_JAR" -H "X-XSRF-TOKEN: $CSRF_TOKEN" \
  -X POST "$BASE/api/runs/$RUN_ID/execute"

curl -fsS -u "$AGENTIC_SDLC_REVIEWER_USERNAME:$AGENTIC_SDLC_REVIEWER_PASSWORD" \
  -b "$COOKIE_JAR" -c "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -H "X-XSRF-TOKEN: $CSRF_TOKEN" -X POST "$BASE/api/runs/$RUN_ID/review" \
  -d '{"decision":"APPROVE","feedback":"Architecture reviewed"}'
```

Repeat the review request for each returned checkpoint, updating the feedback. Valid decisions are `APPROVE` and `REQUEST_CHANGES`. To inspect state and evidence, use:

```bash
curl -fsS -u "$AGENTIC_SDLC_REQUESTER_USERNAME:$AGENTIC_SDLC_REQUESTER_PASSWORD" "$BASE/api/runs/$RUN_ID"
curl -fsS -u "$AGENTIC_SDLC_REQUESTER_USERNAME:$AGENTIC_SDLC_REQUESTER_PASSWORD" "$BASE/api/runs/$RUN_ID/orchestration"
curl -fsS -u "$AGENTIC_SDLC_REQUESTER_USERNAME:$AGENTIC_SDLC_REQUESTER_PASSWORD" "$BASE/api/runs/$RUN_ID/events"
```

For a brownfield run, supply `scenario: "BROWNFIELD"`, relevant `codebaseContext`, and measurable criteria. For ambiguity, create a run with a vague requirement or missing criteria; inspect the assessment questions, then submit clarified input to `POST /api/runs/{id}/replan` with the same CSRF/authentication requirements. Re-planning is prohibited only after final approval.

Example request body for reference:

```json
{
  "requirement": "Refactor the existing billing API to fix invoice rounding.",
  "scenario": "BROWNFIELD",
  "codebaseContext": "billing module; invoice API; reconciliation data flow",
  "acceptanceCriteria": ["Invoice totals follow the documented currency rounding rule"]
}
```

`scenario` is optional and defaults to `AUTO`. The output is a reviewable plan, not evidence that a code change was made. Do not use the prototype to authorize production changes.

## Testing approach

Run the suite with `mvn test`. Unit tests cover intake ambiguity, greenfield/brownfield classification, graph dependencies and gates, state transitions, review blocking, re-planning lineage, bounded retries/fallback/safe-stop, parallel independent stages, and context propagation. H2 repository tests cover restoring run/workflow/audit state and rejecting modified or truncated audit chains. Spring security integration tests cover authenticated access, reviewer-only approvals, CSRF, and authenticated audit actors. These tests exercise the prototype's own workflow; they do not validate a generated application, since none is generated.

## Evaluation fit

| Criterion | Evidence in this prototype | Assessment and boundary |
|---|---|---|
| Agentic orchestration | Dependency DAG, gates, parallel-ready waves, approval holds, retries, fallback, safe-stop, rollback of local outputs, explicit replanning, audit and runtime counters. | Strong for a controlled local prototype; not autonomous agents or distributed job orchestration. |
| Architecture and engineering principles | Separate intake, orchestration, run state, worker, policy, repository, API, and security components; injectable worker/repository boundaries; unit and integration tests. | Modular and testable; H2, process-local locks, and local users do not support multi-instance coordination or enterprise identity. |
| Decomposition and execution | Acceptance-criteria-driven implementation and test plan notes, brownfield context, fixed dependency graph, upstream stage context, parallel test/documentation planning. | Decomposition is more specific than a generic checklist but still heuristic and bounded; no repository discovery or source execution. |
| Output realism | Stage-specific requirement, design, implementation, test, documentation, and release artifacts with supplied criteria/context. | Reviewable planning artifacts only; no production code, schemas, runnable test cases, or release package is generated. |
| Validation and risk control | Ambiguity checks, structural validation, security roles, CSRF, HMAC audit-chain verification, human gates, retry limits, and safe-stop. | Protects this workflow; does not establish target-system correctness, security, compliance, or deployment safety. |
| Decision clarity and engineering judgment | Assumptions, open questions, scope limitations, policy decisions, audit lineage, and trade-offs are exposed in the run/evidence. | Decisions are deterministic and explainable; semantic quality depends on supplied input and human review. |

## Limitations and trade-offs

This is a deterministic demonstration, not production SDLC automation. HTTP Basic uses two local users configured from environment-backed secrets; it is not SSO/enterprise identity or credential lifecycle management. The default H2 database is a local file at `./data/agentic-sdlc`; it persists requirements and audit events without database encryption, and must be protected by host-level access controls. Audit events use a keyed HMAC chain that detects modification/deletion while the audit key remains secret, but the store is not immutable or an external compliance ledger. Parallel stages are local planning workers, not dispatched agents or external jobs. Re-planning is an explicit human-triggered reset, not automatic change detection from upstream agent outputs. Policy enforcement is a local allowlist of disabled capabilities and approval gates, not an enterprise security/compliance policy engine. MTTR, retries, fallbacks, rollback, approval, failure, and latency values are derived from recorded workflow events and are not production reliability measurements. Repository scanning, source mutation, code/test/schema generation, and CI execution are not implemented. Consequently, validation is limited to intake and review-package structure; functional correctness, security, compliance, and release behavior of a target system remain unverified.
