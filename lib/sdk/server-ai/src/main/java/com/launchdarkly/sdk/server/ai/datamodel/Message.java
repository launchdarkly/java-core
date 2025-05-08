package com.launchdarkly.sdk.server.ai.datamodel;

public class Message {
    private String content;

    private Role role;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }
}
