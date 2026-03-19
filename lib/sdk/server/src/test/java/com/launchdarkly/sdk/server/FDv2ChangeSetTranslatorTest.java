package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet.FDv2Change;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet.FDv2ChangeSetType;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet.FDv2ChangeType;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FDv2ChangeSetTranslatorTest extends BaseTest {

  private static JsonElement createFlagJsonElement(String key, int version) {
    String json = String.format(
        "{\n" +
        "  \"key\": \"%s\",\n" +
        "  \"version\": %d,\n" +
        "  \"on\": true,\n" +
        "  \"fallthrough\": {\"variation\": 0},\n" +
        "  \"variations\": [true, false]\n" +
        "}",
        key, version);
    return JsonParser.parseString(json);
  }

  private static JsonElement createSegmentJsonElement(String key, int version) {
    String json = String.format(
        "{\n" +
        "  \"key\": \"%s\",\n" +
        "  \"version\": %d\n" +
        "}",
        key, version);
    return JsonParser.parseString(json);
  }

  @Test
  public void toChangeSet_withFullChangeset_returnsFullChangeSetType() {
    List<FDv2Change> changes = ImmutableList.of(
        new FDv2Change(FDv2ChangeType.PUT, "flag", "flag1", 1, createFlagJsonElement("flag1", 1))
    );
    FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> result =
        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, testLogger, null, true);

    assertEquals(ChangeSetType.Full, result.getType());
  }

  @Test
  public void toChangeSet_withPartialChangeset_returnsPartialChangeSetType() {
    List<FDv2Change> changes = ImmutableList.of(
        new FDv2Change(FDv2ChangeType.PUT, "flag", "flag1", 1, createFlagJsonElement("flag1", 1))
    );
    FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.PARTIAL, changes, Selector.make(1, "state1"));

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> result =
        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, testLogger, null, true);

    assertEquals(ChangeSetType.Partial, result.getType());
  }

  @Test
  public void toChangeSet_withNoneChangeset_returnsNoneChangeSetType() {
    List<FDv2Change> changes = ImmutableList.of();
    FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.NONE, changes, Selector.make(1, "state1"));

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> result =
        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, testLogger, null, true);

    assertEquals(ChangeSetType.None, result.getType());
  }

  @Test
  public void toChangeSet_includesSelector() {
    List<FDv2Change> changes = ImmutableList.of();
    Selector selector = Selector.make(42, "test-state");
    FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, selector);

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> result =
        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, testLogger, null, true);

    assertEquals(selector.getVersion(), result.getSelector().getVersion());
    assertEquals(selector.getState(), result.getSelector().getState());
  }

  @Test
  public void toChangeSet_includesEnvironmentId() {
    List<FDv2Change> changes = ImmutableList.of();
    FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> result =
        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, testLogger, "test-env-id", true);

    assertEquals("test-env-id", result.getEnvironmentId());
  }

  @Test
  public void toChangeSet_withNullEnvironmentId_returnsNullEnvironmentId() {
    List<FDv2Change> changes = ImmutableList.of();
    FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> result =
        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, testLogger, null, true);

    assertNull(result.getEnvironmentId());
  }

  @Test
  public void toChangeSet_withPutOperation_deserializesItem() {
    List<FDv2Change> changes = ImmutableList.of(
        new FDv2Change(FDv2ChangeType.PUT, "flag", "flag1", 1, createFlagJsonElement("flag1", 1))
    );
    FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> result =
        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, testLogger, null, true);

    Map.Entry<DataKind, KeyedItems<ItemDescriptor>> flagData = findDataKind(result, "features");
    assertNotNull(flagData);
    Map.Entry<String, ItemDescriptor> item = getFirstItem(flagData.getValue());
    assertEquals("flag1", item.getKey());
    assertNotNull(item.getValue().getItem());
    assertEquals(1, item.getValue().getVersion());
  }

  @Test
  public void toChangeSet_withDeleteOperation_createsDeletedDescriptor() {
    List<FDv2Change> changes = ImmutableList.of(
        new FDv2Change(FDv2ChangeType.DELETE, "flag", "flag1", 5, null)
    );
    FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.PARTIAL, changes, Selector.make(1, "state1"));

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> result =
        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, testLogger, null, true);

    Map.Entry<DataKind, KeyedItems<ItemDescriptor>> flagData = findDataKind(result, "features");
    assertNotNull(flagData);
    Map.Entry<String, ItemDescriptor> item = getFirstItem(flagData.getValue());
    assertEquals("flag1", item.getKey());
    assertNull(item.getValue().getItem());
    assertEquals(5, item.getValue().getVersion());
  }

  @Test
  public void toChangeSet_withMultipleFlags_groupsByKind() {
    List<FDv2Change> changes = ImmutableList.of(
        new FDv2Change(FDv2ChangeType.PUT, "flag", "flag1", 1, createFlagJsonElement("flag1", 1)),
        new FDv2Change(FDv2ChangeType.PUT, "flag", "flag2", 2, createFlagJsonElement("flag2", 2))
    );
    FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> result =
        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, testLogger, null, true);

    Map.Entry<DataKind, KeyedItems<ItemDescriptor>> flagData = findDataKind(result, "features");
    assertNotNull(flagData);
    assertEquals(2, countItems(flagData.getValue()));
  }

  @Test
  public void toChangeSet_withFlagsAndSegments_createsMultipleDataKinds() {
    List<FDv2Change> changes = ImmutableList.of(
        new FDv2Change(FDv2ChangeType.PUT, "flag", "flag1", 1, createFlagJsonElement("flag1", 1)),
        new FDv2Change(FDv2ChangeType.PUT, "segment", "seg1", 1, createSegmentJsonElement("seg1", 1))
    );
    FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> result =
        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, testLogger, null, true);

    assertEquals(2, countDataKinds(result));
    assertNotNull(findDataKind(result, "features"));
    assertNotNull(findDataKind(result, "segments"));
  }

  @Test
  public void toChangeSet_withUnknownKind_skipsItemAndLogsWarning() {
    List<FDv2Change> changes = ImmutableList.of(
        new FDv2Change(FDv2ChangeType.PUT, "unknown-kind", "item1", 1, createFlagJsonElement("item1", 1)),
        new FDv2Change(FDv2ChangeType.PUT, "flag", "flag1", 1, createFlagJsonElement("flag1", 1))
    );
    FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> result =
        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, testLogger, null, true);

    assertEquals(1, countDataKinds(result));
    assertNotNull(findDataKind(result, "features"));
    assertLogMessageContains(LDLogLevel.WARN, "Unknown data kind 'unknown-kind' in changeset, skipping");
  }

  @Test
  public void toChangeSet_withPutOperationMissingObject_skipsItemAndLogsWarning() {
    List<FDv2Change> changes = ImmutableList.of(
        new FDv2Change(FDv2ChangeType.PUT, "flag", "flag1", 1, null),
        new FDv2Change(FDv2ChangeType.PUT, "flag", "flag2", 2, createFlagJsonElement("flag2", 2))
    );
    FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> result =
        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, testLogger, null, true);

    Map.Entry<DataKind, KeyedItems<ItemDescriptor>> flagData = findDataKind(result, "features");
    assertNotNull(flagData);
    assertEquals(1, countItems(flagData.getValue()));
    assertEquals("flag2", getFirstItem(flagData.getValue()).getKey());
    assertLogMessageContains(LDLogLevel.WARN, "Put operation for flag/flag1 missing object data, skipping");
  }

  @Test
  public void toChangeSet_withEmptyChanges_returnsEmptyData() {
    List<FDv2Change> changes = ImmutableList.of();
    FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> result =
        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, testLogger, null, true);

    assertEquals(0, countDataKinds(result));
  }

  @Test
  public void toChangeSet_withMixedPutAndDelete_handlesAllOperations() {
    List<FDv2Change> changes = ImmutableList.of(
        new FDv2Change(FDv2ChangeType.PUT, "flag", "flag1", 1, createFlagJsonElement("flag1", 1)),
        new FDv2Change(FDv2ChangeType.DELETE, "flag", "flag2", 2, null),
        new FDv2Change(FDv2ChangeType.PUT, "segment", "seg1", 1, createSegmentJsonElement("seg1", 1))
    );
    FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.PARTIAL, changes, Selector.make(1, "state1"));

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> result =
        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, testLogger, null, true);

    assertEquals(2, countDataKinds(result));

    Map.Entry<DataKind, KeyedItems<ItemDescriptor>> flagData = findDataKind(result, "features");
    assertNotNull(flagData);
    assertEquals(2, countItems(flagData.getValue()));

    Map.Entry<String, ItemDescriptor> flag1 = findItem(flagData.getValue(), "flag1");
    assertNotNull(flag1.getValue().getItem());
    assertEquals(1, flag1.getValue().getVersion());

    Map.Entry<String, ItemDescriptor> flag2 = findItem(flagData.getValue(), "flag2");
    assertNull(flag2.getValue().getItem());
    assertEquals(2, flag2.getValue().getVersion());

    Map.Entry<DataKind, KeyedItems<ItemDescriptor>> segmentData = findDataKind(result, "segments");
    assertNotNull(segmentData);
    assertEquals(1, countItems(segmentData.getValue()));
  }

  @Test
  public void toChangeSet_preservesOrderOfChangesWithinKind() {
    List<FDv2Change> changes = ImmutableList.of(
        new FDv2Change(FDv2ChangeType.PUT, "flag", "flag3", 3, createFlagJsonElement("flag3", 3)),
        new FDv2Change(FDv2ChangeType.PUT, "flag", "flag1", 1, createFlagJsonElement("flag1", 1)),
        new FDv2Change(FDv2ChangeType.PUT, "flag", "flag2", 2, createFlagJsonElement("flag2", 2))
    );
    FDv2ChangeSet fdv2ChangeSet = new FDv2ChangeSet(FDv2ChangeSetType.FULL, changes, Selector.make(1, "state1"));

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> result =
        FDv2ChangeSetTranslator.toChangeSet(fdv2ChangeSet, testLogger, null, true);

    Map.Entry<DataKind, KeyedItems<ItemDescriptor>> flagData = findDataKind(result, "features");
    assertNotNull(flagData);
    List<Map.Entry<String, ItemDescriptor>> items = toList(flagData.getValue().getItems());
    assertEquals("flag3", items.get(0).getKey());
    assertEquals("flag1", items.get(1).getKey());
    assertEquals("flag2", items.get(2).getKey());
  }

  // Helper methods

  private Map.Entry<DataKind, KeyedItems<ItemDescriptor>> findDataKind(
      ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet, String kindName) {
    for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> entry : changeSet.getData()) {
      if (entry.getKey().getName().equals(kindName)) {
        return entry;
      }
    }
    return null;
  }

  private Map.Entry<String, ItemDescriptor> getFirstItem(
      KeyedItems<ItemDescriptor> keyedItems) {
    return keyedItems.getItems().iterator().next();
  }

  private Map.Entry<String, ItemDescriptor> findItem(
      KeyedItems<ItemDescriptor> keyedItems, String key) {
    for (Map.Entry<String, ItemDescriptor> entry : keyedItems.getItems()) {
      if (entry.getKey().equals(key)) {
        return entry;
      }
    }
    return null;
  }

  private int countItems(KeyedItems<ItemDescriptor> keyedItems) {
    int count = 0;
    for (@SuppressWarnings("unused") Map.Entry<String, ItemDescriptor> entry : keyedItems.getItems()) {
      count++;
    }
    return count;
  }

  private int countDataKinds(ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet) {
    int count = 0;
    for (@SuppressWarnings("unused") Map.Entry<DataKind, KeyedItems<ItemDescriptor>> entry : changeSet.getData()) {
      count++;
    }
    return count;
  }

  private List<Map.Entry<String, ItemDescriptor>> toList(
      Iterable<Map.Entry<String, ItemDescriptor>> items) {
    List<Map.Entry<String, ItemDescriptor>> list = new ArrayList<>();
    for (Map.Entry<String, ItemDescriptor> item : items) {
      list.add(item);
    }
    return list;
  }

  private void assertLogMessageContains(LDLogLevel level, String expectedMessageSubstring) {
    for (LogCapture.Message message : logCapture.getMessages()) {
      if (message.getLevel() == level && message.getText().contains(expectedMessageSubstring)) {
        return;
      }
    }
    throw new AssertionError("Expected log message at level " + level + " containing: " + expectedMessageSubstring);
  }
}
