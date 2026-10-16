"""Request-scoped user identity. contextvars are copied into tasks we submit to the job queue."""
from contextvars import ContextVar

from app.errors import Unauthenticated

current_user: ContextVar[str | None] = ContextVar("current_user", default=None)


def require_user() -> str:
    user = current_user.get()
    if user is None:
        raise Unauthenticated("Missing authenticated user")
    return user
