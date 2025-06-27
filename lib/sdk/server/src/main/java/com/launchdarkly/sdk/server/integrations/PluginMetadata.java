package com.launchdarkly.sdk.server.integrations;

/**
 * PluginMetadata contains information about a specific plugin implementation
 */
public abstract class PluginMetadata {
  /**
   * @return the name of the plugin implementation
   */
  public abstract String getName();
}
