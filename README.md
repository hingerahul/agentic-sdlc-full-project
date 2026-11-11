# Agentic SDLC Full Project

A Java 21 / Spring Boot 3 REST API prototype for turning a submitted requirement into a reviewable engineering outcome through bounded, multi-step execution.

## Prototype scope and autonomy boundary

The current prototype demonstrates requirement intake, a five-step workflow plan, explicit execution, structural validation, a generated Markdown outcome, and a human review decision. Workflow execution is deterministic and local: **it does not use an LLM, change source code, access repositories, or call external services**. The output is a review package, not a claim that the requirement has been implemented. Runs are stored in memory and disappear when the application restarts.

## Requirements and run

- JDK 21+
- Maven 3.9+

```bash
mvn spring-boot:run
```

The API listens on `http://localhost:8080`. Run the tests with `mvn test`.

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

The planned steps are requirement analysis, engineering decomposition, bounded prototype execution, validation, and review-package generation. Status progresses from `PLANNED` to `REVIEW_REQUIRED`, then to `APPROVED` or `CHANGES_REQUESTED` only after a human submits a review decision. Repeated execution or review in an invalid state returns `409 Conflict`; unknown run IDs return `404 Not Found`; invalid request bodies return `400 Bad Request`.

Validation is intentionally structural (requirement present, expected steps present/completed, execution notes available). It does not prove correctness or implement acceptance criteria. This is an in-memory prototype; persistence, authentication, model-backed planning, repository/code execution, and richer policy controls are future extensions.
