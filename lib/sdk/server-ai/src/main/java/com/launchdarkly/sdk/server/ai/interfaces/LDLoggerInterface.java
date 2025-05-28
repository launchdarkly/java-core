package com.launchdarkly.sdk.server.ai.interfaces;

/**
 * Log interface required by the AI Client.
 */
public interface LDLoggerInterface {

    /**
     * Log an error.
     * 
     * @param format    format string
     * @param allParams parameters
     */
    void error(String format, Object... allParams);

    /**
     * Log a warning.
     * 
     * @param format    format string
     * @param allParams parameters
     */
    void warn(String format, Object... allParams);
}
