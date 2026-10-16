package com.knowledgehub.config;

import org.springframework.core.task.TaskDecorator;

/** Copies the caller's {@link UserContext} onto the executor thread and always clears it afterwards. */
public class UserContextTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        String userId = UserContext.get();
        return () -> {
            String previous = UserContext.get();
            try {
                if (userId != null) {
                    UserContext.set(userId);
                } else {
                    UserContext.clear();
                }
                runnable.run();
            } finally {
                if (previous != null) {
                    UserContext.set(previous);
                } else {
                    UserContext.clear();
                }
            }
        };
    }
}
