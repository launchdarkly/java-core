package com.launchdarkly.sdk.server.ai.config;

/**
 * Represents an AI Config, which contains model parameters and prompt messages.
 */
public class LDAiConfig {
    /**
     * Represents a single message, which is part of a prompt.
     */
    public class Message {
        /**
         * The content of the message, which may contain Mustache templates.
         */
        private final String content;

        /**
         * The role of the message.
         */
        private final String role;

        /**
         * Constructor of the Message
         * @param content content of the message
         * @param role role of the message
         */
        public Message(String content, String role) {
            this.content = content;
            this.role = role;
        }
    }
}
