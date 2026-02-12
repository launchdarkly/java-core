package com.launchdarkly.sdk.internal.events;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.EventSummarizer.EventSummary;

import java.util.Collections;
import java.util.List;

/**
 * Aggregates events from all contexts into a single summary event.
 * <p>
 * This implementation combines all flag evaluations across all contexts into one
 * summary event (without context information), which is the behavior for server-side SDKs.
 * <p>
 * Note that the methods of this class are deliberately not thread-safe, because they should
 * always be called from EventProcessor's single message-processing thread.
 */
final class AggregatedEventSummarizer implements EventSummarizerInterface {
  private final EventSummarizer summarizer;

  AggregatedEventSummarizer() {
    this.summarizer = new EventSummarizer();
  }

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
    summarizer.summarizeEvent(timestamp, flagKey, flagVersion, variation, value, defaultValue, context);
  }

  @Override
  public List<EventSummary> getSummariesAndReset() {
    EventSummary summary = summarizer.getSummaryAndReset();
    // Always return a list with exactly one summary for consistency with interface
    return Collections.singletonList(summary);
  }

  @Override
  public void restoreTo(List<EventSummary> previousSummaries) {
    // In aggregated mode, we only restore the first summary (should only be one anyway)
    if (!previousSummaries.isEmpty()) {
      summarizer.restoreTo(previousSummaries.get(0));
    }
  }

  @Override
  public boolean isEmpty() {
    return summarizer.isEmpty();
  }

  @Override
  public void clear() {
    summarizer.clear();
  }
}
