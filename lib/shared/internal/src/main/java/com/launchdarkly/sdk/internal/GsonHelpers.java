package com.launchdarkly.sdk.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.launchdarkly.sdk.internal.fdv2.payloads.IntentCode;

/**
 * General-purpose Gson helpers.
 */
public abstract class GsonHelpers {
  private static final Gson GSON_INSTANCE = new GsonBuilder()
      .registerTypeAdapter(IntentCode.class, new IntentCode.IntentCodeTypeAdapter())
      .create();
  
  /**
   * A singleton instance of Gson with the default configuration.
   * @return a Gson instance
   */
  public static Gson gsonInstance() {
    return GSON_INSTANCE;
  }
}
