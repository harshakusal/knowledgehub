class AppError(Exception):
    status = 500
    title = "Internal Server Error"

    def __init__(self, detail: str = ""):
        super().__init__(detail)
        self.detail = detail


class NotFound(AppError):
    status, title = 404, "Not Found"


class BadRequest(AppError):
    status, title = 400, "Bad Request"


class Unauthenticated(AppError):
    status, title = 401, "Unauthorized"


class LlmUnavailable(AppError):
    """Retries exhausted or circuit open -> 503."""
    status, title = 503, "Service Unavailable"


class Busy(AppError):
    """Bounded job queue full -> 503 + Retry-After."""
    status, title = 503, "Service Unavailable"


class LlmClientError(AppError):
    """Non-retryable upstream error (400/401...) -> 502."""
    status, title = 502, "Bad Gateway"

    def __init__(self, upstream_status: int, detail: str):
        super().__init__(detail)
        self.upstream_status = upstream_status
