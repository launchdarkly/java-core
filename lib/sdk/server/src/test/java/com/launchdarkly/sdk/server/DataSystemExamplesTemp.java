package com.launchdarkly.sdk.server;

/**
 * Temporary file to verify data system configuration examples compile correctly.
 * This file should be deleted after verification.
 */
public class DataSystemExamplesTemp {

    // Example 1: Default configuration
    public void defaultConfiguration() {
        LDConfig config = new LDConfig.Builder()
            .dataSystem(Components.dataSystem().defaultMode())
            .build();

        // SDK key is passed to LDClient constructor
        // LDClient client = new LDClient("your-sdk-key", config);
    }

    // Example 2: Polling only
    public void pollingOnly() {
        LDConfig config = new LDConfig.Builder()
            .dataSystem(Components.dataSystem().polling())
            .build();
    }

    // Example 3: Streaming only
    public void streamingOnly() {
        LDConfig config = new LDConfig.Builder()
            .dataSystem(Components.dataSystem().streaming())
            .build();
    }

    // Example 4: With a persistent store (pattern only - requires actual persistence integration)
    // public void withPersistentStore() {
    //     // Using a hypothetical Redis integration:
    //     LDConfig config = new LDConfig.Builder()
    //         .dataSystem(Components.dataSystem().persistentStore(
    //             Components.persistentDataStore(Redis.dataStore())))
    //         .build();
    // }

    // Example 5: Daemon mode (pattern only - requires actual persistence integration)
    // public void daemonMode() {
    //     // Using a hypothetical Redis integration:
    //     LDConfig config = new LDConfig.Builder()
    //         .dataSystem(Components.dataSystem().daemon(
    //             Components.persistentDataStore(Redis.dataStore())))
    //         .build();
    // }

    // Example 6: Offline mode
    public void offlineMode() {
        LDConfig config = new LDConfig.Builder()
            .offline(true)
            .build();
    }

    // Example 7: Offline with data system (data system is ignored)
    public void offlineWithDataSystem() {
        LDConfig config = new LDConfig.Builder()
            .offline(true)
            .dataSystem(Components.dataSystem().defaultMode())
            .build();
    }

    // Example 8: Custom configuration
    public void customConfiguration() {
        LDConfig config = new LDConfig.Builder()
            .dataSystem(Components.dataSystem().custom()
                .initializers(DataSystemComponents.pollingInitializer())
                .synchronizers(
                    DataSystemComponents.streamingSynchronizer(),
                    DataSystemComponents.pollingSynchronizer()
                )
                .fDv1FallbackSynchronizer(DataSystemComponents.fDv1Polling()))
            .build();
    }
}
