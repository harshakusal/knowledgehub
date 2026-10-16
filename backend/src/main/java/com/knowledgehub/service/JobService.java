package com.knowledgehub.service;

import com.knowledgehub.exception.NotFoundException;
import com.knowledgehub.model.Job;
import com.knowledgehub.model.JobStatus;
import com.knowledgehub.repository.JobRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JobService {
    private final JobRepository jobs;
    private final Clock clock;

    public JobService(JobRepository jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    public Job create(String ownerId, UUID documentId) {
        Job job = new Job(UUID.randomUUID(), ownerId, documentId, JobStatus.QUEUED, null, null, clock.instant());
        jobs.save(job);
        return job;
    }

    public void update(UUID jobId, JobStatus status, String result, String error) {
        jobs.findById(jobId).ifPresent(j -> jobs.save(j.withStatus(status, result, error)));
    }

    /** Jobs are owner-scoped like documents: someone else's job id is a 404. */
    public Job get(UUID jobId, String userId) {
        return jobs.findById(jobId).filter(j -> j.ownerId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Job not found"));
    }
}
