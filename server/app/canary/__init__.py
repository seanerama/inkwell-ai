"""Deploy canary (Stage 8): one live ``canvas.ask`` job proves every deploy.

``fixture.png`` (a committed, deterministically rendered note reading ``what is 1+9=?``)
and ``run.py`` (the ``inkwell canary`` implementation) live here. The runtime only reads
the committed PNG bytes; the Pillow-based generator (``server/scripts``) is dev-only and
never imported at runtime.
"""
