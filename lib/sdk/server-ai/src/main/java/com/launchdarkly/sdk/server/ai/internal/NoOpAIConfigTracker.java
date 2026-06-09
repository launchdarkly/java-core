package com.launchdarkly.sdk.server.ai.internal;

import com.launchdarkly.sdk.server.ai.LDAIConfigTracker;

/**
 * The no-op {@link LDAIConfigTracker} used until metric reporting is implemented in a later step of
 * the AI SDK. It is immutable and stateless, so a single shared instance is safe to reuse.
 * <p>
 * This class is an internal implementation detail and is not part of the supported API.
 */
public final class NoOpAIConfigTracker implements LDAIConfigTracker {
  /**
   * The shared instance.
   */
  public static final NoOpAIConfigTracker INSTANCE = new NoOpAIConfigTracker();

  private NoOpAIConfigTracker() {
  }
}
