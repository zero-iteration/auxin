"""gt-api: read-only query surfaces. Depends on `analysis`, `collector`, `store`."""

from ax_server.api.http import make_api_router
from ax_server.api.service import QueryService

__all__ = ["QueryService", "make_api_router"]
