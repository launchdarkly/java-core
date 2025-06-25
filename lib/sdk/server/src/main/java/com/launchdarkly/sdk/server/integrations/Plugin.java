package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.LDClient;

import java.util.Collections;
import java.util.List;

/**
 * Abstract class that you can extend to create a plugin to the LaunchDarkly SDK.
 */
public abstract class Plugin {
  /**
   * @return the {@link PluginMetadata} that gives details about the plugin.
   */
  public abstract PluginMetadata getMetadata();

  /**
   * Registers the plugin with the SDK. Called once during SDK initialization.
   * The SDK initialization will typically not have been completed at this point, so the plugin should take appropriate
   * actions to ensure the SDK is ready before sending track events or evaluating flags.
   *
   * @param client for the plugin to use
   * @param metadata metadata about the environment where the plugin is running.
   */
  public abstract void register(LDClient client, EnvironmentMetadata metadata);

  /**
   * Gets a list of hooks that the plugin wants to register.
   * This method will be called once during SDK initialization before the register method is called.
   * If the plugin does not need to register any hooks, this method doesn't need to be implemented.
   *
   * @param metadata metadata about the environment where the plugin is running.
   * @return
   */
  public List<Hook> getHooks(EnvironmentMetadata metadata) {
    return Collections.emptyList();
  }
}
