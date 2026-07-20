package com.launchdarkly.sdk.server.ai.internal;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LDSLF4J;
import com.launchdarkly.logging.Logs;

/**
 * Shared logging utilities for the AI SDK.
 * <p>
 * Centralizes the SLF4J-with-console-fallback adapter selection so no individual class needs to
 * replicate the {@code Class.forName} probe.
 */
public final class Loggers {
  /** The logger name used throughout the AI SDK. */
  public static final String BASE_LOGGER_NAME = "LaunchDarkly.AI";

  private Loggers() {}

  /**
   * Returns the default {@link LDLogger} for the AI SDK.
   * <p>
   * Uses SLF4J if {@code org.slf4j.LoggerFactory} is on the classpath; otherwise falls back to
   * console output.
   *
   * @return a logger named {@value #BASE_LOGGER_NAME}
   */
  public static LDLogger defaultLogger() {
    LDLogAdapter adapter;
    try {
      Class.forName("org.slf4j.LoggerFactory");
      adapter = LDSLF4J.adapter();
    } catch (ClassNotFoundException e) {
      adapter = Logs.toConsole();
    }
    return LDLogger.withAdapter(adapter, BASE_LOGGER_NAME);
  }
}
