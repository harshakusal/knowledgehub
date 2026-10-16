"""~chunk_size "tokens" (whitespace words) with chunk_overlap overlap; Markdown headings -> section_heading."""
from __future__ import annotations

import re

from app.models import TextChunk


class ChunkingService:
    def __init__(self, size: int = 500, overlap: int = 50):
        if size <= 0 or overlap < 0 or overlap >= size:
            raise ValueError("Invalid chunk size/overlap")
        self.size, self.overlap = size, overlap

    def chunk(self, text: str) -> list[TextChunk]:
        words: list[tuple[str, str | None]] = []
        heading: str | None = None
        for line in text.splitlines():
            stripped = line.strip()
            if stripped.startswith("#"):
                heading = re.sub(r"^#+\s*", "", stripped)[:255]
            words.extend((w, heading) for w in stripped.split())
        chunks: list[TextChunk] = []
        step = self.size - self.overlap
        start = 0
        while start < len(words):
            end = min(len(words), start + self.size)
            chunks.append(TextChunk(len(chunks), words[start][1], " ".join(w for w, _ in words[start:end])))
            if end == len(words):
                break
            start += step
        return chunks
