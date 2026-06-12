package com.launchdarkly.sdk.server.ai;

/**
 * Reports events related to a single AI run of an {@link AIConfig}.
 * <p>
 * A tracker is obtained from a retrieved config via {@link AIConfig#createTracker()}. Each tracker
 * corresponds to one AI run and is used to record metrics such as model usage, duration, and
 * feedback against the AI Config it was created from.
 * <p>
 * <strong>This interface is an intentional placeholder.</strong> The metric- and feedback-reporting
 * methods (and resumption-token support) are introduced in a later step of the AI SDK build-out; it
 * is defined here so that the public config types expose a stable {@code createTracker()} surface.
 * The only implementation in this release is an internal no-op.
 */
public interface LDAIConfigTracker {
}
