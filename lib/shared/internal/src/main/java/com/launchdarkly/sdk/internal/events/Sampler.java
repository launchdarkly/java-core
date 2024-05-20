package com.launchdarkly.sdk.internal.events;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Class used for event sampling.
 */
public final class Sampler {
  /**
   * Given a ratio determine if an event should be sampled.
   *
   * @param ratio the sampling ratio
   * @return true if it should be sampled
   */
  public static boolean shouldSample(long ratio) {
    if(ratio == 1) {
      return true;
    }
    if(ratio == 0) {
      return false;
    }

    // Checking for any number in the range will have approximately a 1 in X
    // chance. So we check for 0 as it is part of any range.
    // This random number is not used for cryptographic purposes.
    return ThreadLocalRandom.current().nextLong(ratio) == 0;
  }
}
