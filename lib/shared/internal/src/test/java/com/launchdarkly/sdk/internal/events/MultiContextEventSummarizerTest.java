package com.launchdarkly.sdk.internal.events;

import com.launchdarkly.sdk.ContextBuilder;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.EventSummarizer.EventSummary;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MultiContextEventSummarizerTest {
  private static final LDContext context1 = LDContext.create("user-key-1");
  private static final LDContext context2 = LDContext.create("user-key-2");
  private static final LDContext context3 = LDContext.builder("org-key-1").kind("organization").build();

  @Test
  public void summarizeEventCreatesNewSummarizerForNewContext() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);

    List<EventSummary> summaries = mcs.getSummariesAndReset();
    assertEquals(1, summaries.size());
    assertEquals(context1, summaries.get(0).context);
    assertFalse(summaries.get(0).isEmpty());
  }

  @Test
  public void summarizeEventRoutesToCorrectContextSummarizer() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    // Add events for two different contexts
    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);
    mcs.summarizeEvent(1001, "flag2", 22, 2, LDValue.of("value2"), LDValue.of("default2"), context2);

    List<EventSummary> summaries = mcs.getSummariesAndReset();
    assertEquals(2, summaries.size());

    // Find summaries by context (order not guaranteed)
    EventSummary summary1 = findSummaryByContext(summaries, context1);
    EventSummary summary2 = findSummaryByContext(summaries, context2);

    assertNotNull(summary1);
    assertNotNull(summary2);
    assertEquals(1, summary1.counters.size());
    assertEquals(1, summary2.counters.size());
    assertTrue(summary1.counters.containsKey("flag1"));
    assertTrue(summary2.counters.containsKey("flag2"));
  }

  @Test
  public void summarizeEventAccumulatesForSameContext() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    // Add multiple events for the same context
    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);
    mcs.summarizeEvent(1001, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);
    mcs.summarizeEvent(1002, "flag2", 22, 2, LDValue.of("value2"), LDValue.of("default2"), context1);

    List<EventSummary> summaries = mcs.getSummariesAndReset();
    assertEquals(1, summaries.size());

    EventSummary summary = summaries.get(0);
    assertEquals(context1, summary.context);
    assertEquals(2, summary.counters.size());

    // Check that flag1 was counted twice
    assertEquals(2, summary.counters.get("flag1").versionsAndVariations.get(11).get(1).count);
  }

  @Test
  public void multipleDifferentContextsProduceMultipleSummaries() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    // Add events for three different contexts
    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);
    mcs.summarizeEvent(1001, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context2);
    mcs.summarizeEvent(1002, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context3);

    List<EventSummary> summaries = mcs.getSummariesAndReset();
    assertEquals(3, summaries.size());

    // Verify each summary has the correct context
    EventSummary summary1 = findSummaryByContext(summaries, context1);
    EventSummary summary2 = findSummaryByContext(summaries, context2);
    EventSummary summary3 = findSummaryByContext(summaries, context3);

    assertNotNull(summary1);
    assertNotNull(summary2);
    assertNotNull(summary3);
  }

  @Test
  public void getSummariesAndResetClearsState() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);

    List<EventSummary> summaries1 = mcs.getSummariesAndReset();
    assertEquals(1, summaries1.size());

    // After reset, should be empty
    assertTrue(mcs.isEmpty());

    List<EventSummary> summaries2 = mcs.getSummariesAndReset();
    assertEquals(0, summaries2.size());
  }

  @Test
  public void isEmptyReturnsTrueWhenNoEvents() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();
    assertTrue(mcs.isEmpty());
  }

  @Test
  public void isEmptyReturnsFalseWhenEventsExist() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);

    assertFalse(mcs.isEmpty());
  }

  @Test
  public void clearRemovesAllSummaries() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);
    mcs.summarizeEvent(1001, "flag2", 22, 2, LDValue.of("value2"), LDValue.of("default2"), context2);

    assertFalse(mcs.isEmpty());

    mcs.clear();

    assertTrue(mcs.isEmpty());
    List<EventSummary> summaries = mcs.getSummariesAndReset();
    assertEquals(0, summaries.size());
  }

  @Test
  public void getSummariesAndResetFiltersEmptySummaries() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    // Add events
    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);

    // Get summaries (this resets the state, creating new empty summarizers)
    List<EventSummary> summaries = mcs.getSummariesAndReset();
    assertEquals(1, summaries.size());

    // Now the internal state is reset, so getting summaries again should return empty list
    List<EventSummary> emptySummaries = mcs.getSummariesAndReset();
    assertEquals(0, emptySummaries.size());
  }

  @Test
  public void contextWithSameKeyButDifferentKindCreatesMultipleSummaries() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    LDContext userContext = LDContext.builder("same-key").kind("user").build();
    LDContext orgContext = LDContext.builder("same-key").kind("organization").build();

    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), userContext);
    mcs.summarizeEvent(1001, "flag2", 22, 2, LDValue.of("value2"), LDValue.of("default2"), orgContext);

    List<EventSummary> summaries = mcs.getSummariesAndReset();
    assertEquals(2, summaries.size());
  }

  @Test
  public void multiKindContextCreatesOneSummary() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    LDContext multiContext = LDContext.createMulti(
        LDContext.create("user-key"),
        LDContext.builder("org-key").kind("organization").build()
    );

    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), multiContext);
    mcs.summarizeEvent(1001, "flag2", 22, 2, LDValue.of("value2"), LDValue.of("default2"), multiContext);

    List<EventSummary> summaries = mcs.getSummariesAndReset();
    assertEquals(1, summaries.size());
    assertEquals(multiContext, summaries.get(0).context);
    assertEquals(2, summaries.get(0).counters.size());
  }

  @Test
  public void timestampsAreTrackedPerContext() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);
    mcs.summarizeEvent(2000, "flag2", 22, 2, LDValue.of("value2"), LDValue.of("default2"), context2);
    mcs.summarizeEvent(1500, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);

    List<EventSummary> summaries = mcs.getSummariesAndReset();
    assertEquals(2, summaries.size());

    EventSummary summary1 = findSummaryByContext(summaries, context1);
    EventSummary summary2 = findSummaryByContext(summaries, context2);

    assertEquals(1000, summary1.startDate);
    assertEquals(1500, summary1.endDate);
    assertEquals(2000, summary2.startDate);
    assertEquals(2000, summary2.endDate);
  }

  @Test
  public void contextKindsAreTrackedPerContext() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    LDContext multiContext1 = LDContext.createMulti(
        LDContext.create("user-key-1"),
        LDContext.builder("org-key-1").kind("organization").build()
    );

    LDContext multiContext2 = LDContext.createMulti(
        LDContext.create("user-key-2"),
        LDContext.builder("device-key-1").kind("device").build()
    );

    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), multiContext1);
    mcs.summarizeEvent(1001, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), multiContext2);

    List<EventSummary> summaries = mcs.getSummariesAndReset();
    assertEquals(2, summaries.size());

    EventSummary summary1 = findSummaryByContext(summaries, multiContext1);
    EventSummary summary2 = findSummaryByContext(summaries, multiContext2);

    // Summary1 should have user and organization kinds for flag1
    assertTrue(summary1.counters.get("flag1").contextKinds.contains("user"));
    assertTrue(summary1.counters.get("flag1").contextKinds.contains("organization"));

    // Summary2 should have user and device kinds for flag1
    assertTrue(summary2.counters.get("flag1").contextKinds.contains("user"));
    assertTrue(summary2.counters.get("flag1").contextKinds.contains("device"));
  }

  @Test
  public void manyContextsHandledCorrectly() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();
    int contextCount = 100;

    // Add events for many different contexts
    for (int i = 0; i < contextCount; i++) {
      LDContext context = LDContext.create("user-key-" + i);
      mcs.summarizeEvent(1000 + i, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context);
    }

    List<EventSummary> summaries = mcs.getSummariesAndReset();
    assertEquals(contextCount, summaries.size());

    // Verify each summary is non-empty and has the correct context
    for (EventSummary summary : summaries) {
      assertNotNull(summary.context);
      assertFalse(summary.isEmpty());
      assertEquals(1, summary.counters.size());
    }
  }

  @Test
  public void restoreToRestoresPreviousState() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    // Add events for two contexts
    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);
    mcs.summarizeEvent(1001, "flag2", 22, 2, LDValue.of("value2"), LDValue.of("default2"), context2);

    // Get summaries (this clears the state)
    List<EventSummary> summaries = mcs.getSummariesAndReset();
    assertEquals(2, summaries.size());

    // Verify state is now empty
    assertTrue(mcs.isEmpty());

    // Restore the previous state
    mcs.restoreTo(summaries);

    // Verify state was restored
    assertFalse(mcs.isEmpty());

    // Get summaries again and verify they match
    List<EventSummary> restoredSummaries = mcs.getSummariesAndReset();
    assertEquals(2, restoredSummaries.size());

    EventSummary restored1 = findSummaryByContext(restoredSummaries, context1);
    EventSummary restored2 = findSummaryByContext(restoredSummaries, context2);

    assertNotNull(restored1);
    assertNotNull(restored2);
    assertTrue(restored1.counters.containsKey("flag1"));
    assertTrue(restored2.counters.containsKey("flag2"));
  }

  @Test
  public void restoreToHandlesEmptySummaries() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    // Add an event
    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);
    assertFalse(mcs.isEmpty());

    // Create an empty summary list
    List<EventSummary> emptySummaries = new ArrayList<>();

    // Restore to empty list
    mcs.restoreTo(emptySummaries);

    // Should now be empty
    assertTrue(mcs.isEmpty());
  }

  @Test
  public void restoreToPreservesCountsAndTimestamps() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    // Add multiple events for same flag/context
    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);
    mcs.summarizeEvent(1500, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);
    mcs.summarizeEvent(2000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);

    // Get summaries
    List<EventSummary> summaries = mcs.getSummariesAndReset();
    EventSummary original = summaries.get(0);

    // Verify original state
    assertEquals(1000, original.startDate);
    assertEquals(2000, original.endDate);
    assertEquals(3, original.counters.get("flag1").versionsAndVariations.get(11).get(1).count);

    // Restore
    mcs.restoreTo(summaries);

    // Get summaries again and verify timestamps and counts preserved
    List<EventSummary> restored = mcs.getSummariesAndReset();
    EventSummary restoredSummary = restored.get(0);

    assertEquals(1000, restoredSummary.startDate);
    assertEquals(2000, restoredSummary.endDate);
    assertEquals(3, restoredSummary.counters.get("flag1").versionsAndVariations.get(11).get(1).count);
  }

  @Test
  public void restoreToAllowsContinuedAccumulation() {
    MultiContextEventSummarizer mcs = new MultiContextEventSummarizer();

    // Add events
    mcs.summarizeEvent(1000, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);

    // Get and restore
    List<EventSummary> summaries = mcs.getSummariesAndReset();
    mcs.restoreTo(summaries);

    // Add more events to the same context
    mcs.summarizeEvent(1500, "flag1", 11, 1, LDValue.of("value1"), LDValue.of("default1"), context1);

    // Verify counts accumulated
    List<EventSummary> finalSummaries = mcs.getSummariesAndReset();
    EventSummary finalSummary = finalSummaries.get(0);

    // Should have count of 2 (1 original + 1 new)
    assertEquals(2, finalSummary.counters.get("flag1").versionsAndVariations.get(11).get(1).count);
    // Timestamps should span from first to last
    assertEquals(1000, finalSummary.startDate);
    assertEquals(1500, finalSummary.endDate);
  }

  private EventSummary findSummaryByContext(List<EventSummary> summaries, LDContext context) {
    for (EventSummary summary : summaries) {
      if (context.equals(summary.context)) {
        return summary;
      }
    }
    return null;
  }
}
