-- V1: initial schema. READ-ONLY once merged: schema changes go in a new numbered migration.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE documents (
    id          UUID PRIMARY KEY,
    owner_id    VARCHAR(64)  NOT NULL,
    title       VARCHAR(255) NOT NULL,
    content     TEXT         NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_documents_owner ON documents (owner_id, created_at DESC);

-- Embedding dimension must match LLM_EMBEDDING_DIM (default 384) and the mock-llm EMBEDDING_DIM.
CREATE TABLE chunks (
    id              BIGSERIAL PRIMARY KEY,
    document_id     UUID         NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    owner_id        VARCHAR(64)  NOT NULL,
    title           VARCHAR(255) NOT NULL,
    section_heading VARCHAR(255),
    chunk_index     INT          NOT NULL,
    content         TEXT         NOT NULL,
    embedding       vector(384)  NOT NULL,
    UNIQUE (document_id, chunk_index)
);
CREATE INDEX idx_chunks_owner ON chunks (owner_id);
CREATE INDEX idx_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);
