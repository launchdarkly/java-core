package com.launchdarkly.sdk.internal.events;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates separate summary events per context. Maintains separate EventSummarizer instances
 * for each unique context, allowing generation of multiple summary events per flush interval.
 * This implementation is intended for use by client-side SDKs.
 * <p>
 * This implementation creates one summary event per context, each including the context
 * information, enabling "who got what when" analytics.
 * <p>
 * Note that the methods of this class are deliberately not thread-safe, because they should
 * always be called from EventProcessor's single message-processing thread.
 */
final class PerContextEventSummarizer implements EventSummarizerInterface {
  private final Map<LDContext, EventSummarizer> summarizersByContext;

  PerContextEventSummarizer() {
    this.summarizersByContext = new HashMap<>();
  }

  /**
   * Adds information about an evaluation to the appropriate context's summarizer.
   *
   * @param timestamp    the millisecond timestamp
   * @param flagKey      the flag key
   * @param flagVersion  the flag version, or -1 if the flag is unknown
   * @param variation    the result variation, or -1 if none
   * @param value        the result value
   * @param defaultValue the application default value
   * @param context      the evaluation context
   */
  @Override
  public void summarizeEvent(
    long timestamp,
    String flagKey,
    int flagVersion,
    int variation,
    LDValue value,
    LDValue defaultValue,
    LDContext context
  ) {
    // Get or create summarizer for this context
    EventSummarizer summarizer = summarizersByContext.computeIfAbsent(context, EventSummarizer::new);

    // Delegate to the per-context summarizer
    summarizer.summarizeEvent(timestamp, flagKey, flagVersion, variation, value, defaultValue, context);
  }

  /**
   * Gets all current summarized event data (one per context), and resets the state to empty.
   *
   * @return list of summary states, one for each context that had events
   */
  @Override
  public List<EventSummarizer.EventSummary> getSummariesAndReset() {
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
   * Restores the summarizer state from a previous snapshot. This is used when a flush
   * operation fails, and we need to keep the summary data for the next attempt.
   *
   * @param previousSummaries the list of summaries to restore
   */
  @Override
  public void restoreTo(List<EventSummarizer.EventSummary> previousSummaries) {
    summarizersByContext.clear();
    for (EventSummarizer.EventSummary summary : previousSummaries) {
      if (summary.context != null && !summary.isEmpty()) {
        EventSummarizer summarizer = new EventSummarizer(summary.context);
        summarizer.restoreTo(summary);
        summarizersByContext.put(summary.context, summarizer);
      }
    }
  }

  /**
   * Returns true if there is no summary data for any context.
   *
   * @return true if all contexts have are empty
   */
  @Override
  public boolean isEmpty() {
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
  @Override
  public void clear() {
    summarizersByContext.clear();
  }
}
