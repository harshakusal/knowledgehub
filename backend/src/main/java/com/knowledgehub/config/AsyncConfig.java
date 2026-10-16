package com.knowledgehub.config;

import com.knowledgehub.client.Sleeper;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Dedicated, bounded pool for everything that talks to the LLM (ingestion embeddings,
     * summaries, RAG answers, SSE streams) so slow LLM calls never occupy Tomcat request threads.
     *
     * <p>Rejection policy: {@link ThreadPoolExecutor.AbortPolicy}. When the pool and queue are full
     * we fail fast with {@code TaskRejectedException}, which the API maps to 503 + Retry-After.
     * CallerRunsPolicy was rejected because it would run the LLM call on the web thread — exactly the
     * freeze we are trying to avoid; Discard policies would silently lose jobs.
     */
    @Bean(name = "llmExecutor")
    public ThreadPoolTaskExecutor llmExecutor(AppProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("llm-");
        executor.setCorePoolSize(props.executor().coreSize());
        executor.setMaxPoolSize(props.executor().maxSize());
        executor.setQueueCapacity(props.executor().queueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(new UserContextTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    @Bean
    public Sleeper sleeper() {
        return duration -> Thread.sleep(duration.toMillis());
    }
}
