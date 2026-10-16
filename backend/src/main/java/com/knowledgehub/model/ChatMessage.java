package com.knowledgehub.model;

public record ChatMessage(String role, String content) {
    public static ChatMessage system(String c) {
        return new ChatMessage("system", c);
    }

    public static ChatMessage user(String c) {
        return new ChatMessage("user", c);
    }
}
