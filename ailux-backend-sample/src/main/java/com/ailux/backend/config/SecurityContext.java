package com.ailux.backend.config;

/**
 * Simple thread-local holder for the current authenticated user ID.
 * Not using Spring Security to keep the sample minimal.
 */
public class SecurityContext {

    private static final ThreadLocal<String> currentUserId = new ThreadLocal<>();

    public static void setUserId(String userId) {
        currentUserId.set(userId);
    }

    public static String getUserId() {
        return currentUserId.get();
    }

    public static void clear() {
        currentUserId.remove();
    }
}
