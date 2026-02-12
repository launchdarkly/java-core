package com.launchdarkly.sdk.internal.events;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.EventSummarizer.EventSummary;

import java.util.List;

/**
 * Interface for event summarization strategies. Implementations can provide either
 * single-summary (aggregated) or per-context summary behavior.
 * <p>
 * Note that implementations are deliberately not thread-safe, as they should always
 * be called from EventProcessor's single message-processing thread.
 */
interface EventSummarizerInterface {
  /**
   * Adds information about an evaluation to the summary.
   *
   * @param timestamp    the millisecond timestamp
   * @param flagKey      the flag key
   * @param flagVersion  the flag version, or -1 if the flag is unknown
   * @param variation    the result variation, or -1 if none
   * @param value        the result value
   * @param defaultValue the application default value
   * @param context      the evaluation context
   */
  void summarizeEvent(
    long timestamp,
    String flagKey,
    int flagVersion,
    int variation,
    LDValue value,
    LDValue defaultValue,
    LDContext context
  );

  /**
   * Gets all current summarized event data and resets the state to empty.
   *
   * @return list of summary states (may contain one or many summaries depending on implementation)
   */
  List<EventSummary> getSummariesAndReset();

  /**
   * Restores the summarizer state from a previous snapshot. This is used when a flush
   * operation fails, and we need to keep the summary data for the next attempt.
   *
   * @param previousSummaries the list of summaries to restore
   */
  void restoreTo(List<EventSummary> previousSummaries);

  /**
   * Returns true if there is no summary data.
   *
   * @return true if the state is empty
   */
  boolean isEmpty();

  /**
   * Clears all summary data.
   */
  void clear();
}
