# Agentic SDLC Full Project

A Java 21 / Spring Boot 3 REST API prototype for turning a submitted requirement into a reviewable engineering outcome through bounded, multi-step execution.

## Deliverables status

This repository currently delivers a runnable prototype and the foundation for a governed orchestration model. It is intentionally bounded and does not claim to perform real code generation or repository mutation.

The prototype covers the following outcomes:

- Working prototype (runnable end-to-end)
- Architecture overview of the core components and lifecycle
- Example execution patterns for greenfield, brownfield, and ambiguous requirements
- Setup instructions
- Testing approach, limitations, and trade-offs

## Prototype scope and autonomy boundary

The current prototype demonstrates:

- requirement intake
- workflow decomposition into explicit steps
- deterministic execution of a bounded local workflow
- structural validation
- generation of a Markdown review artifact
- human review decision processing

The system is deliberately constrained to prevent unsafe autonomy. It does not:

- change repository files
- call external systems
- generate production code automatically
- approve work without an explicit human decision

This design is intentionally conservative and suitable for demonstrating controlled-autonomy behavior in a prototype, not for fully autonomous software delivery.

## Architecture overview

The core components are:

1. Run intake layer
   - receives a requirement through the REST API
   - validates minimum and maximum request sizes
   - creates a tracked run record

2. Workflow execution service
   - decomposes the requirement into explicit work items
   - executes a bounded lifecycle
   - records outputs, checks, and validation status

3. Review gate
   - requires explicit human review before a run can be approved
   - records `APPROVE` or `REQUEST_CHANGES`
   - preserves the decision and feedback for auditability

4. Validation layer
   - checks structural completeness of the requirement and execution notes
   - ensures the execution produced a reviewable artifact
   - intentionally avoids claiming functional correctness

5. Orchestration model (governance layer)
   - models stages as dependency-aware nodes
   - supports gate checks and approval dependencies
   - tracks metrics such as completion rate, retries, rollback state, and latency
   - provides traceability for execution decisions and review checkpoints

The orchestration model is intentionally layered so the project can evolve from a prototype into a more complete controlled SDLC engine without changing the run API contract first.

## Requirements and run

- JDK 21+
- Maven 3.9+

```bash
mvn spring-boot:run
```

The API listens on `http://localhost:8080`.

Run the tests with:

```bash
mvn test
```

## API workflow

1. `POST /api/runs` — submit a requirement of 20–10,000 characters; returns a `PLANNED` run and its decomposed work items.
2. `POST /api/runs/{id}/execute` — execute the bounded local workflow once; returns validation results and a Markdown outcome in `outcome`, with status `REVIEW_REQUIRED`.
3. Inspect `GET /api/runs/{id}` and the `outcome` field.
4. `POST /api/runs/{id}/review` — record the human decision `APPROVE` or `REQUEST_CHANGES`, optionally with feedback. No run is approved automatically.
5. `GET /api/runs` — list runs.

Example:

```bash
curl -X POST http://localhost:8080/api/runs \
  -H 'Content-Type: application/json' \
  -d '{"requirement":"Build a working prototype that transforms a requirement into a reviewable engineering outcome using controlled, multi-step execution."}'
```

Use the returned `id` to execute and review the run:

```bash
curl -X POST http://localhost:8080/api/runs/REPLACE_WITH_ID/execute

curl -X POST http://localhost:8080/api/runs/REPLACE_WITH_ID/review \
  -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE","feedback":"Reviewed the generated plan and validation."}'
```

## Workflow and statuses

The planned steps are requirement analysis, engineering decomposition, bounded prototype execution, validation, and review-package generation. Status progresses from `PLANNED` to `REVIEW_REQUIRED`, then to `APPROVED` or `CHANGES_REQUESTED` after human review.

The prototype uses a deterministic local workflow and intentionally avoids repository-side or external-side execution. This narrows the system to a safe, inspectable proof-of-concept rather than a full autonomous engineering platform.

## Scenario examples

### 1) Greenfield scenario

Problem type: new system, clear requirement, no existing implementation constraints.

Example requirement:

> Build a lightweight API that accepts a requirement, plans a workflow, validates the generated output, and requires human approval before completion.

Expected decomposition:

- normalize requirement
- break it into engineering tasks
- create a bounded execution plan
- validate the generated outcome
- produce a human review package

Expected orchestration behavior:

- stage dependencies resolve cleanly
- no ambiguous assumptions are introduced
- validation passes structurally
- human approval is the final gate

### 2) Brownfield scenario

Problem type: existing codebase, changing constraints, partial system context.

Example requirement:

> Extend an existing service to add a review checkpoint before deployment while preserving backward compatibility and documenting the risk boundaries.

Expected decomposition:

- identify impacted modules and interfaces
- assess compatibility and risky change points
- plan updates and migration considerations
- validate the reviewability of the change
- package the recommendation with limitations

Expected orchestration behavior:

- design-review stage becomes important
- dependency gates enforce sequencing and risk-aware review
- governance asks for explicit approval before continuing through high-risk stages

### 3) Ambiguous scenario

Problem type: vague or incomplete requirement with missing context.

Example requirement:

> Build a better SDLC automation system for our team.

Expected decomposition:

- clarify scope and constraints
- identify missing assumptions
- separate must-haves from nice-to-haves
- document ambiguity and risks
- create a review package with assumptions and unresolved items

Expected orchestration behavior:

- requirement analysis flags ambiguity
- decomposition includes clarification steps
- validation checks whether assumptions were recorded
- review package makes uncertainty explicit instead of silently proceeding

## Validation model

Validation is intentionally structural. It checks whether:

- the requirement is present
- expected workflow steps are present and completed
- execution notes exist for each step
- a summary artifact can be constructed

This does not prove functional correctness or production readiness. It validates the run shape and reviewability rather than demonstrating a complete engineering solution.

## Testing approach

The test suite focuses on the prototype’s key lifecycle guarantees:

- requirement creation
- run execution
- review approval or change request
- preventing repeated execution of the same run
- orchestration graph validity and metric generation

This is intentionally narrow and deterministic. It verifies the software’s workflow control and review gate behavior without pretending to validate repository-level engineering quality.

## Limitations and trade-offs

This prototype is intentionally conservative and therefore limited in several ways:

- It does not generate or modify source code.
- It does not integrate with GitHub repositories or external CI/CD systems.
- It does not model real-time multi-agent concurrency or dependency scheduling.
- It does not attempt production-grade security or compliance enforcement.
- It validates structure rather than implementation correctness.

The trade-off is deliberate: a smaller, safer prototype is easier to reason about and easier to review. It shows the control pattern and governance model without crossing into unsafe autonomous execution.

## Summary

This project demonstrates a bounded, reviewable agentic SDLC workflow that is suitable for prototype validation and architectural exploration. It is a strong starting point for a more complete controlled-autonomy platform, but it does not yet represent full autonomous software engineering.

The key idea is simple: the system can decompose a requirement into an explicit workflow, validate the resulting artifact, and require a human to actively review and approve the outcome before it is treated as complete.
