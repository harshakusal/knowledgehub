package com.knowledgehub.config;

import com.knowledgehub.exception.UnauthenticatedException;

/**
 * Holds the authenticated user id for the current thread. Set by {@link UserIdFilter} on web
 * threads and copied onto executor threads by {@link UserContextTaskDecorator}.
 */
public final class UserContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(String userId) {
        CURRENT.set(userId);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static String require() {
        String userId = CURRENT.get();
        if (userId == null) {
            throw new UnauthenticatedException("Missing authenticated user");
        }
        return userId;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
