package com.knowledgehub.service;

import static org.assertj.core.api.Assertions.*;

import com.knowledgehub.config.AppProperties;
import com.knowledgehub.config.AsyncConfig;
import com.knowledgehub.config.UserContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class AsyncExecutorTest {
    private final ThreadPoolTaskExecutor exec = new AsyncConfig().llmExecutor(
            new AppProperties(new AppProperties.Executor(1, 1, 1), null, null));

    @AfterEach
    void tearDown() {
        exec.shutdown();
        UserContext.clear();
    }

    @Test
    void propagatesUserContextAndClearsAfterwards() throws Exception {
        UserContext.set("alice");
        AtomicReference<String> seen = new AtomicReference<>();
        CompletableFuture.runAsync(() -> seen.set(UserContext.get()), exec).get(5, TimeUnit.SECONDS);
        assertThat(seen.get()).isEqualTo("alice");
        UserContext.clear();
        CompletableFuture.runAsync(() -> seen.set(UserContext.get()), exec).get(5, TimeUnit.SECONDS);
        assertThat(seen.get()).isNull();
    }

    @Test
    void rejectsWhenPoolAndQueueFull() throws Exception {
        CountDownLatch block = new CountDownLatch(1);
        exec.execute(() -> {
            try {
                block.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        exec.execute(() -> { });
        assertThatThrownBy(() -> exec.execute(() -> { })).isInstanceOf(TaskRejectedException.class);
        block.countDown();
    }
}
