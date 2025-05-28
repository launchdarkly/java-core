package com.launchdarkly.sdk.server.ai.interfaces;

import com.launchdarkly.sdk.server.ai.datamodel.AIConfig;
import com.launchdarkly.sdk.server.ai.tracking.Feedback;
import com.launchdarkly.sdk.server.ai.tracking.Response;
import com.launchdarkly.sdk.server.ai.tracking.Usage;
import java.util.concurrent.CompletableFuture;

/**
 * A utility capable of generating events related to a specific AI model
 * configuration.
 */
public interface LDAIConfigTrackerInterface {
    
    /**
     * The AI model configuration retrieved from LaunchDarkly, or a default value if unable to retrieve.
     */
    AIConfig getConfig();
    
    /**
     * Tracks a duration metric related to this config. For example, if a particular operation
     * related to usage of the AI model takes 100ms, this can be tracked and made available in
     * LaunchDarkly.
     */
    void trackDuration(float durationMs);
    
    /**
     * Tracks the duration of a task, and returns the result of the task.
     *
     * If the provided task throws, then this method will also throw.
     *
     * In the case the provided function throws, this function will still
     * record the duration.
     */
    <T> CompletableFuture<T> trackDurationOfTask(CompletableFuture<T> task);
    
    /**
     * Tracks the time it takes for the first token to be generated.
     */
    void trackTimeToFirstToken(float timeToFirstTokenMs);
    
    /**
     * Tracks feedback (positive or negative) related to the output of the model.
     */
    void trackFeedback(Feedback feedback);
    
    /**
     * Tracks a generation event related to this config.
     */
    void trackSuccess();
    
    /**
     * Tracks an unsuccessful generation event related to this config.
     */
    void trackError();
    
    /**
     * Tracks a request to a provider. The request is a task that returns a Response, which
     * contains information about the request such as token usage and metrics.
     *
     * This function will track the duration of the operation, the token
     * usage, and the success or error status.
     *
     * If the provided function throws, then this method will also throw.
     *
     * In the case the provided function throws, this function will record the
     * duration and an error.
     */
    CompletableFuture<Response> trackRequest(CompletableFuture<Response> request);
    
    /**
     * Tracks token usage related to this config.
     */
    void trackTokens(Usage usage);
}
