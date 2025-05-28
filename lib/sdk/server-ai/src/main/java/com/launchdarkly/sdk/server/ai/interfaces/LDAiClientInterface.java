package com.launchdarkly.sdk.server.ai.interfaces;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.server.ai.datamodel.AIConfig;
import java.util.Map;

/**
 * Represents the interface of the AI client, useful for mocking.
 */
public interface LDAIClientInterface {
    
    /**
     * Retrieves a LaunchDarkly AI Config identified by the given key. The return value
     * is an {@link LDAIConfigTrackerInterface}, which makes the configuration available and
     * provides convenience methods for generating events related to model usage.
     *
     * Any variables provided will be interpolated into the prompt's messages.
     * Additionally, the current LaunchDarkly context will be available as 'ldctx' within
     * a prompt message.
     *
     * @param key the AI Config key
     * @param context the context
     * @param defaultValue the default config, if unable to retrieve from LaunchDarkly
     * @param variables the list of variables used when interpolating the prompt
     * @return an AI Config tracker
     */
    LDAIConfigTrackerInterface config(String key, LDContext context, AIConfig defaultValue, Map<String, Object> variables);
}
