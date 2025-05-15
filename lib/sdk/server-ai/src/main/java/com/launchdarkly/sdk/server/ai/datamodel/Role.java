package com.launchdarkly.sdk.server.ai.datamodel;

/**
 * Represents the role of the prompt message.
 */
public enum Role {
    /**
     * User Role
     */
    USER("user"),
    /**
     * System Role
     */
    SYSTEM("system"),
    /**
     * Assistant Role
     */
    ASSISTANT("assistant");

    private final String role;

    private Role(String role) {
        this.role = role;
    }

    public static Role getRole(String role) {
        switch (role) {
            case "user":
                return USER;
            case "system":
                return SYSTEM;
            case "assistant":
                return ASSISTANT;
            default:
                return null;
        }
    }

    @Override
    public String toString() {
        return role;
    }
}
