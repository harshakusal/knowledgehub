"""Caps concurrent SSE streams (each holds an upstream LLM connection). Over the cap -> Busy (503)."""
from app.errors import Busy


class StreamLimiter:
    def __init__(self, limit: int):
        self.limit, self.active = limit, 0

    def acquire(self) -> None:
        if self.active >= self.limit:
            raise Busy("Too many concurrent answers, please retry shortly")
        self.active += 1

    def release(self) -> None:
        self.active = max(0, self.active - 1)
