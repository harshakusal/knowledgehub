package com.knowledgehub.controller;

import com.knowledgehub.config.UserContext;
import com.knowledgehub.dto.JobResponse;
import com.knowledgehub.service.SummaryService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JobController {
    private final SummaryService summaries;

    public JobController(SummaryService summaries) {
        this.summaries = summaries;
    }

    @GetMapping("/api/jobs/{jobId}")
    public JobResponse get(@PathVariable UUID jobId) {
        return summaries.getJob(jobId, UserContext.require());
    }
}
