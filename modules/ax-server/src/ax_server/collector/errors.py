"""Collector-layer errors."""

from ax_server.collector.health import RejectReason

__all__ = ["IngestRejected", "RejectReason"]


class IngestRejected(Exception):
    """One window was refused. Carries the stable reason and an HTTP status."""

    def __init__(self, reason: str, detail: str, *, status: int = 422) -> None:
        self.reason = reason
        self.detail = detail
        self.status = status
        super().__init__(f"{reason}: {detail}")
