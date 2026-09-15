# 0009. Defer the in-app help agent feature

- **Status:** Accepted (deferred; re-offer after Phase 3)
- **Date:** 2026-09-15

## Context

The drop-in catalog offers one feature, `helper-bot`: a restricted help mode of the
app's own chat loop with a read-only tool registry, log access, draft-then-confirm
GitHub issue filing, and a living FAQ. Its prerequisites are a chat/LLM loop, a web UI
surface, and structured logs.

## Decision

**Not accepted for the initial backlog.** Inkwell has an LLM loop but no web UI, one
user, and no support surface to serve. Two of its architectural requirements are
adopted anyway because they are cheap and useful on their own:

- Structured JSON logging on the server from Stage 0 (`logs/app.log`, one JSON object
  per line, request id and job id on every line).
- A `?` entry point is reserved in the client toolbar design but not built.

The feature is re-offered by the Architect once Phase 3 (spaces) is live and the
"agent as a space" model exists, at which point a `help` space with a read-only tool
set would be the natural fit and the retrofit recipe applies.

## Alternatives considered

- **Accept now, build dark behind `HELP_ENABLED`.** Six stages of work ahead of the
  ink loop that the spec says contains every hard problem.

## Consequences

- The planner receives no helper-bot stages. Structured logging is folded into
  Stage 0.
