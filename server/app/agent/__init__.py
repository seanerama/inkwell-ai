"""Agent runtime (stub in Stage 1).

The model call, prompt assembly, structured-output parsing, and two-tier validation
(SPEC §10.2-10.4, ADR-0006) land in a later stage. The ``anthropic`` SDK is installed
now but unused. Stage 5 will register the ``canvas.*`` handlers via
``app.jobs.register_handler``.
"""
