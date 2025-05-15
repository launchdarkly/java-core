package com.launchdarkly.sdk.server.ai.datamodel;

public final class Message {
    private String content;

    private Role role;

    public Message(String content, Role role) {
        this.content = content;
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public Role getRole() {
        return role;
    }
}
