package com.knowledgehub.repository;

import com.knowledgehub.model.Document;
import com.knowledgehub.model.DocumentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentRepository {
    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Document d) {
        jdbc.sql("INSERT INTO documents (id, owner_id, title, content, status, created_at) VALUES (?, ?, ?, ?, ?, ?)")
                .params(d.id(), d.ownerId(), d.title(), d.content(), d.status().name(), Timestamp.from(d.createdAt()))
                .update();
    }

    public Optional<Document> findById(UUID id) {
        return jdbc.sql("SELECT * FROM documents WHERE id = ?").param(id).query(DocumentRepository::map).optional();
    }

    /** Every list query is owner-scoped; there is deliberately no findAll(). */
    public List<Document> findByOwner(String ownerId, String titleQuery, int limit) {
        String like = "%" + (titleQuery == null ? "" : titleQuery.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")) + "%";
        return jdbc.sql("SELECT * FROM documents WHERE owner_id = ? AND title ILIKE ? ORDER BY created_at DESC LIMIT ?")
                .params(ownerId, like, limit)
                .query(DocumentRepository::map).list();
    }

    public void updateStatus(UUID id, DocumentStatus status) {
        jdbc.sql("UPDATE documents SET status = ? WHERE id = ?").params(status.name(), id).update();
    }

    public boolean deleteByIdAndOwner(UUID id, String ownerId) {
        return jdbc.sql("DELETE FROM documents WHERE id = ? AND owner_id = ?").params(id, ownerId).update() > 0;
    }

    private static Document map(ResultSet rs, int row) throws SQLException {
        return new Document(rs.getObject("id", UUID.class), rs.getString("owner_id"), rs.getString("title"),
                rs.getString("content"), DocumentStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant());
    }
}
