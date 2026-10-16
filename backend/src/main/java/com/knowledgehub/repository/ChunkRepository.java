package com.knowledgehub.repository;

import com.knowledgehub.model.Document;
import com.knowledgehub.model.RetrievedChunk;
import com.knowledgehub.model.TextChunk;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ChunkRepository {
    private final JdbcClient jdbc;

    public ChunkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void replaceChunks(Document doc, List<TextChunk> chunks, List<float[]> embeddings) {
        jdbc.sql("DELETE FROM chunks WHERE document_id = ?").param(doc.id()).update();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk c = chunks.get(i);
            jdbc.sql("""
                    INSERT INTO chunks (document_id, owner_id, title, section_heading, chunk_index, content, embedding)
                    VALUES (?, ?, ?, ?, ?, ?, ?::vector)""")
                    .params(doc.id(), doc.ownerId(), doc.title(), c.sectionHeading(), c.chunkIndex(), c.text(),
                            toVectorLiteral(embeddings.get(i)))
                    .update();
        }
    }

    /** Top-k cosine similarity, ALWAYS restricted to the owner; optionally to a subset of their documents. */
    public List<RetrievedChunk> searchSimilar(String ownerId, Collection<UUID> documentIds, float[] query, int k) {
        String docFilter = documentIds.isEmpty() ? "" : " AND document_id IN (:docIds)";
        var spec = jdbc.sql("""
                SELECT document_id, chunk_index, title, section_heading, content, embedding <=> CAST(:q AS vector) AS distance
                FROM chunks WHERE owner_id = :owner""" + docFilter + " ORDER BY distance LIMIT :k")
                .param("q", toVectorLiteral(query))
                .param("owner", ownerId)
                .param("k", k);
        if (!documentIds.isEmpty()) {
            spec = spec.param("docIds", documentIds);
        }
        return spec.query((rs, n) -> new RetrievedChunk(rs.getObject("document_id", UUID.class), rs.getInt("chunk_index"),
                rs.getString("title"), rs.getString("section_heading"), rs.getString("content"), rs.getDouble("distance"))).list();
    }

    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
