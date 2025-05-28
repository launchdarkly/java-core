package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.LDValue;

public final class Message {
    public static class MessageConverter extends LDValue.Converter<Message> {
        @Override
        public LDValue fromType(Message message) {
            return LDValue.buildObject()
                    .put("content", message.getContent())
                    .put("role", message.getRole().toString())
                    .build();
        }

        @Override
        public Message toType(LDValue ldValue) {
            return Message.builder()
                    .content(ldValue.get("content").stringValue())
                    .role(Role.getRole(ldValue.get("role").stringValue()))
                    .build();
        }
    }

    private final String content;

    private final Role role;

    Message(String content, Role role) {
        this.content = content;
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public Role getRole() {
        return role;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String content;
        private Role role;

        private Builder() {
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder role(Role role) {
            this.role = role;
            return this;
        }

        public Message build() {
            return new Message(content, role);
        }
    }
}
