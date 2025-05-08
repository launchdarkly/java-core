package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;
import com.launchdarkly.sdk.server.ai.interfaces.LDAiClientInterface;

/**
 * The LaunchDarkly AI client. The client is capable of retrieving AI Configs from LaunchDarkly,
 * and generating events specific to usage of the AI Config when interacting with model providers.
 */
public class LDAiClient implements LDAiClientInterface {
    private LDClientInterface client;
    private LDLogger logger;

    /**
     * Creates a {@link LDAiClient}
     * 
     * @param client LaunchDarkly Java Server SDK 
     */
    public LDAiClient(LDClientInterface client) {
        if(client == null) {
            //Error
        } else {
            this.client = client;
            this.logger = client.getLogger();
        }
    }
}
