package com.launchdarkly.sdk.internal.events;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages per-context event summarization. Maintains separate EventSummarizer instances
 * for each unique context, allowing generation of multiple summary events per flush interval.
 * <p>
 * Note that the methods of this class are deliberately not thread-safe, because they should
 * always be called from EventProcessor's single message-processing thread.
 */
final class MultiContextEventSummarizer {
  private final Map<LDContext, EventSummarizer> summarizersByContext;

  MultiContextEventSummarizer() {
    this.summarizersByContext = new HashMap<>();
  }

  /**
   * Adds information about an evaluation to the appropriate context's summarizer.
   *
   * @param timestamp the millisecond timestamp
   * @param flagKey the flag key
   * @param flagVersion the flag version, or -1 if the flag is unknown
   * @param variation the result variation, or -1 if none
   * @param value the result value
   * @param defaultValue the application default value
   * @param context the evaluation context
   */
  void summarizeEvent(
      long timestamp,
      String flagKey,
      int flagVersion,
      int variation,
      LDValue value,
      LDValue defaultValue,
      LDContext context
      ) {
    // Get or create summarizer for this context
    EventSummarizer summarizer = summarizersByContext.get(context);
    if (summarizer == null) {
      summarizer = new EventSummarizer(context);
      summarizersByContext.put(context, summarizer);
    }

    // Delegate to the per-context summarizer
    summarizer.summarizeEvent(timestamp, flagKey, flagVersion, variation, value, defaultValue, context);
  }

  /**
   * Gets all current summarized event data (one per context), and resets the state to empty.
   *
   * @return list of summary states, one for each context that had events
   */
  List<EventSummarizer.EventSummary> getSummariesAndReset() {
    List<EventSummarizer.EventSummary> summaries = new ArrayList<>();
    for (EventSummarizer summarizer : summarizersByContext.values()) {
      EventSummarizer.EventSummary summary = summarizer.getSummaryAndReset();
      if (!summary.isEmpty()) {
        summaries.add(summary);
      }
    }
    summarizersByContext.clear();
    return summaries;
  }

  /**
   * Returns true if there is no summary data for any context.
   *
   * @return true if all contexts have empty state
   */
  boolean isEmpty() {
    for (EventSummarizer summarizer : summarizersByContext.values()) {
      if (!summarizer.isEmpty()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Clears all summarizers and context tracking.
   */
  void clear() {
    summarizersByContext.clear();
  }
}
