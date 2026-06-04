package com.launchdarkly.sdk.server.ai.internal;

/**
 * Internal constants identifying this AI SDK package, reported once per {@code LDAIClient} via the
 * {@code $ld:ai:sdk:info} event.
 * <p>
 * This class is for internal use only and is not part of the supported public API.
 */
public final class AISdkInfo {
  /** The published name of this AI SDK package. */
  public static final String AI_SDK_NAME = "launchdarkly-java-server-sdk-ai";

  /** The implementation language of this AI SDK. */
  public static final String AI_SDK_LANGUAGE = "java";

  /** The version of this AI SDK. */
  // This constant is updated automatically by release-please when the project version changes.
  // x-release-please-start-version
  public static final String AI_SDK_VERSION = "0.1.0";
  // x-release-please-end

  private AISdkInfo() {
  }
}
