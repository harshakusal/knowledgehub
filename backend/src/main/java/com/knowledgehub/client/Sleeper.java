package com.knowledgehub.client;

import java.time.Duration;

/** Abstraction over Thread.sleep so retry back-off can be asserted in tests without real waiting. */
@FunctionalInterface
public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
}
