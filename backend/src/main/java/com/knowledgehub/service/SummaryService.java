package com.knowledgehub.service;

import com.knowledgehub.dto.JobResponse;
import com.knowledgehub.model.Job;
import com.knowledgehub.model.JobStatus;
import java.util.UUID;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

@Service
public class SummaryService {
    private final DocumentPermissionService permissions;
    private final JobService jobs;
    private final SummaryWorker worker;

    public SummaryService(DocumentPermissionService permissions, JobService jobs, SummaryWorker worker) {
        this.permissions = permissions;
        this.jobs = jobs;
        this.worker = worker;
    }

    /** Creates a job and hands it to the async worker. Pool saturation propagates as TaskRejectedException (503). */
    public JobResponse requestSummary(UUID documentId, String userId) {
        permissions.requireReadable(documentId, userId);
        Job job = jobs.create(userId, documentId);
        try {
            worker.summarize(job.id(), documentId);
        } catch (TaskRejectedException e) {
            jobs.update(job.id(), JobStatus.FAILED, null, "Server busy");
            throw e;
        }
        return JobResponse.from(job);
    }

    public JobResponse getJob(UUID jobId, String userId) {
        return JobResponse.from(jobs.get(jobId, userId));
    }
}
