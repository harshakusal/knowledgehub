package com.knowledgehub.repository;

import com.knowledgehub.model.Job;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** In-memory job store (single instance). Would move to a table / Redis for multi-instance deployments. */
@Repository
public class JobRepository {
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();

    public void save(Job job) {
        jobs.put(job.id(), job);
    }

    public Optional<Job> findById(UUID id) {
        return Optional.ofNullable(jobs.get(id));
    }
}
