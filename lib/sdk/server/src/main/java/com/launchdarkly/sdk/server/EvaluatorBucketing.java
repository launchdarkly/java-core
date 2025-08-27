package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;

import org.apache.commons.codec.digest.DigestUtils;

/**
 * Encapsulates the logic for percentage rollouts.
 */
abstract class EvaluatorBucketing {
  private EvaluatorBucketing() {}
  
  private static final float LONG_SCALE = (float) 0xFFFFFFFFFFFFFFFL;

  // Computes a bucket value for a rollout or experiment. If an error condition prevents
  // us from computing a valid bucket value, we return 0, which will cause the evaluator
  // to select the first bucket. A special case is if no context of the desired kind is
  // found, in which case we return the special value -1; this similarly will cause the
  // first bucket to be chosen (since it is less than the end value of the bucket, just
  // as 0 is), but also tells the evaluator that inExperiment must be set to false.
  static float computeBucketValue(
      boolean isExperiment,
      Integer seed,
      LDContext context,
      ContextKind contextKind,
      String flagOrSegmentKey,
      AttributeRef attr,
      String salt
      ) {
    LDContext matchContext = context.getIndividualContext(contextKind);
    if (matchContext == null) {
      return -1;
    }
    LDValue contextValue;
    if (isExperiment || attr == null) {
      contextValue = LDValue.of(matchContext.getKey());
    } else {
      if (!attr.isValid()) {
        return 0;
      }
      contextValue = matchContext.getValue(attr);
      if (contextValue.isNull()) {
        return 0;
      }
    }

    StringBuilder keyBuilder = new StringBuilder();
    if (seed != null) {
      keyBuilder.append(seed.intValue());
    } else {
      keyBuilder.append(flagOrSegmentKey).append('.').append(salt);
    }
    keyBuilder.append('.');
    if (!getBucketableStringValue(keyBuilder, contextValue)) {
      return 0;
    }

    // turn the first 15 hex digits of this into a long
    byte[] hash = DigestUtils.sha1(keyBuilder.toString());
    long longVal = 0;
    for (int i = 0; i < 7; i++) {
      longVal <<= 8;
      longVal |= (hash[i] & 0xff);
    }
    longVal <<= 4;
    longVal |= ((hash[7] >> 4) & 0xf);
    return (float) longVal / LONG_SCALE;
  }

  private static boolean getBucketableStringValue(StringBuilder keyBuilder, LDValue userValue) {
    switch (userValue.getType()) { 
    case STRING:
      keyBuilder.append(userValue.stringValue());
      return true;
    case NUMBER:
      if (userValue.isInt()) {
        keyBuilder.append(userValue.intValue());
        return true;
      }
      return false;
    default:
      return false;
    }
  }
}
