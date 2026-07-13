package com.launchdarkly.sdk.server.ai.internal;

/**
 * Identifying information about this AI SDK, reported once per client via the
 * {@code $ld:ai:sdk:info} event.
 * <p>
 * This class is an internal implementation detail and is not part of the supported API.
 */
public final class AISdkInfo {
  /**
   * The published artifact name of this SDK.
   */
  public static final String NAME = "launchdarkly-java-server-sdk-ai";

  /**
   * The implementation language reported to LaunchDarkly.
   */
  public static final String LANGUAGE = "java";

  /**
   * The SDK version.
   * <p>
   * This must be kept in step with the {@code version} in {@code gradle.properties} (which
   * {@code release-please} updates on release). It is a plain constant rather than a manifest
   * lookup so that it resolves correctly in unit tests and when the classes are used outside the
   * packaged jar.
   */
  // x-release-please-start-version
  public static final String VERSION = "0.2.0";
  // x-release-please-end

  private AISdkInfo() {
  }
}
