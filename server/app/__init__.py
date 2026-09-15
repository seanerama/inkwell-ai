"""Inkwell AI server package."""

import os

# The running version is DERIVED from the release tag (Verity framework-spec): the
# release workflow passes it as a Docker build arg that the runtime stage exports as
# INKWELL_VERSION. Local and test runs fall back to a clearly non-release marker.
__version__ = os.environ.get("INKWELL_VERSION") or "0.0.0-dev"
