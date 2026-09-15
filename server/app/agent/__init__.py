"""Agent runtime (SPEC §10.2-10.4, ADR-0006/0007).

- ``prompt`` assembles the system prompt in the fixed SPEC §10.3 order.
- ``client`` makes the Anthropic structured-output call.
- ``validate`` extracts, validates (schema + semantic), and retries once.

``run_agent`` is the entry point used by the ``canvas.annotate`` handler and by later
job types.
"""

from app.agent.validate import AgentRun, AgentValidationError, run_agent

__all__ = ["run_agent", "AgentRun", "AgentValidationError"]
