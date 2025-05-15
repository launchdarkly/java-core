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
            return new Message(ldValue.get("content").stringValue(), Role.getRole(ldValue.get("role").stringValue()));
        }
    }

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
