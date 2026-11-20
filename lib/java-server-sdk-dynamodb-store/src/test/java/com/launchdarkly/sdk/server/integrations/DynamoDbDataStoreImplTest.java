package com.launchdarkly.sdk.server.integrations;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.launchdarkly.sdk.server.DataModel;
import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.SerializedItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.PersistentDataStore;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.launchdarkly.sdk.server.TestComponents.clientContext;
import static com.launchdarkly.sdk.server.integrations.TestUtils.baseDataStoreBuilder;
import static com.launchdarkly.sdk.server.integrations.TestUtils.clearEverything;
import static com.launchdarkly.sdk.server.integrations.CollectionHelpers.mapOf;
import static com.launchdarkly.sdk.server.integrations.DynamoDbStoreImplBase.PARTITION_KEY;
import static com.launchdarkly.sdk.server.integrations.DynamoDbStoreImplBase.SORT_KEY;
import static com.launchdarkly.sdk.server.integrations.TestUtils.TABLE_NAME;
import static com.launchdarkly.sdk.server.integrations.TestUtils.createTestClient;
import static com.launchdarkly.sdk.server.integrations.TestUtils.createTableIfNecessary;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Runs the standard database data store test suite that's defined in the Java SDK.
 * <p>
 * Note that you must be running a local DynamoDB instance on port 8000 to run these tests.
 * The simplest way to do this is:
 * <pre>
 *     docker run -p 8000:8000 amazon/dynamodb-local
 * </pre>
 */
@SuppressWarnings("javadoc")
public class DynamoDbDataStoreImplTest extends PersistentDataStoreTestBase<DynamoDbDataStoreImpl> {
  private static final String BAD_ITEM_KEY = "baditem";
  private static final String TOMBSTONE_PREFIX = "tombstonetest";
  private static final String TOMBSTONE_KEY = "deleted-flag";
  
  @BeforeClass
  public static void setUpAll() {
    createTableIfNecessary();
  }
  
  @Override
  protected ComponentConfigurer<PersistentDataStore> buildStore(String prefix) {
    return baseDataStoreBuilder().prefix(prefix);
  }
  
  @Override
  protected void clearAllData() {
    clearEverything(null);
  }
  
  @Override
  protected boolean setUpdateHook(DynamoDbDataStoreImpl storeUnderTest, final Runnable hook) {
    storeUnderTest.setUpdateHook(hook);
    return true;
  }

  @Test
  public void dataStoreSkipsAndLogsTooLargeItemOnInitForFlag() throws Exception {
    dataStoreSkipsAndLogsTooLargeItemOnInit(DataModel.FEATURES, 0);
  }

  @Test
  public void dataStoreSkipsAndLogsTooLargeItemOnInitForSegment() throws Exception {
    dataStoreSkipsAndLogsTooLargeItemOnInit(DataModel.SEGMENTS, 1);
  }

  @Test
  public void dataStoreSkipsAndLogsTooLargeItemOnUpsertForFlag() throws Exception {
    dataStoreSkipsAndLogsTooLargeItemOnUpsert(DataModel.FEATURES);
  }

  @Test
  public void dataStoreSkipsAndLogsTooLargeItemOnUpsertForSegment() throws Exception {
    dataStoreSkipsAndLogsTooLargeItemOnUpsert(DataModel.SEGMENTS);
  }

  @Test
  public void upsertOfDeletedItemStoresTombstoneJson() throws Exception {
    clearAllData();
    try (PersistentDataStore store = buildStore(TOMBSTONE_PREFIX).build(makeClientContext())) {
      store.init(new FullDataSet<>(ImmutableList.of()));
      ItemDescriptor deleted = ItemDescriptor.deletedItem(7);
      store.upsert(DataModel.FEATURES, TOMBSTONE_KEY,
          new SerializedItemDescriptor(7, true, DataModel.FEATURES.serialize(deleted)));

      assertEquals("{\"version\":7,\"deleted\":true}", getRawItemJson(DataModel.FEATURES, TOMBSTONE_KEY));
      assertReadsAsDeleted(DataModel.FEATURES, store.get(DataModel.FEATURES, TOMBSTONE_KEY), 7);
      assertReadsAsDeleted(DataModel.FEATURES, store.getAll(DataModel.FEATURES).getItems().iterator().next().getValue(), 7);
    }
  }

  @Test
  public void initWithDeletedItemStoresTombstoneJson() throws Exception {
    clearAllData();
    try (PersistentDataStore store = buildStore(TOMBSTONE_PREFIX).build(makeClientContext())) {
      ItemDescriptor deleted = ItemDescriptor.deletedItem(3);
      store.init(new FullDataSet<>(ImmutableList.of(
          new SimpleEntry<>(DataModel.SEGMENTS, new KeyedItems<>(ImmutableList.of(
              new SimpleEntry<>(TOMBSTONE_KEY,
                  new SerializedItemDescriptor(3, true, DataModel.SEGMENTS.serialize(deleted)))))))));

      assertEquals("{\"version\":3,\"deleted\":true}", getRawItemJson(DataModel.SEGMENTS, TOMBSTONE_KEY));
      assertReadsAsDeleted(DataModel.SEGMENTS, store.get(DataModel.SEGMENTS, TOMBSTONE_KEY), 3);
    }
  }

  @Test
  public void deletedItemWithoutSerializedFormStoresTombstoneJson() throws Exception {
    clearAllData();
    try (PersistentDataStore store = buildStore(TOMBSTONE_PREFIX).build(makeClientContext())) {
      store.init(new FullDataSet<>(ImmutableList.of()));
      store.upsert(DataModel.FEATURES, TOMBSTONE_KEY, new SerializedItemDescriptor(4, true, null));

      assertEquals("{\"version\":4,\"deleted\":true}", getRawItemJson(DataModel.FEATURES, TOMBSTONE_KEY));
    }
  }

  @Test
  public void legacyNullPlaceholderReadsAsDeleted() throws Exception {
    clearAllData();
    try (PersistentDataStore store = buildStore(TOMBSTONE_PREFIX).build(makeClientContext())) {
      store.init(new FullDataSet<>(ImmutableList.of()));
      createTestClient().putItem(builder -> builder.tableName(TABLE_NAME).item(mapOf(
          PARTITION_KEY, AttributeValue.builder().s(TOMBSTONE_PREFIX + ":" + DataModel.FEATURES.getName()).build(),
          SORT_KEY, AttributeValue.builder().s(TOMBSTONE_KEY).build(),
          "version", AttributeValue.builder().n("5").build(),
          "item", AttributeValue.builder().s("null").build())));

      SerializedItemDescriptor item = store.get(DataModel.FEATURES, TOMBSTONE_KEY);
      assertTrue(item.isDeleted());
      assertEquals(5, item.getVersion());
      assertNull(item.getSerializedItem());

      SerializedItemDescriptor fromAll = store.getAll(DataModel.FEATURES).getItems().iterator().next().getValue();
      assertTrue(fromAll.isDeleted());
      assertEquals(5, fromAll.getVersion());
    }
  }

  private static String getRawItemJson(DataKind kind, String key) {
    Map<String, AttributeValue> item = createTestClient().getItem(builder -> builder.tableName(TABLE_NAME).key(mapOf(
        PARTITION_KEY, AttributeValue.builder().s(TOMBSTONE_PREFIX + ":" + kind.getName()).build(),
        SORT_KEY, AttributeValue.builder().s(key).build()))).item();
    return item.get("item").s();
  }

  private static void assertReadsAsDeleted(DataKind kind, SerializedItemDescriptor item, int version) {
    assertEquals(version, item.getVersion());
    assertEquals(ItemDescriptor.deletedItem(version), kind.deserialize(item.getSerializedItem()));
  }

  private void dataStoreSkipsAndLogsTooLargeItemOnInit(
      DataKind dataKind,
      int collIndex
      ) throws Exception {
    SerializedItemDescriptor badItem = makeTooBigItem(dataKind);
    
    // Construct a data set that is based on the hard-coded data from makeGoodData(), but with one
    // oversized item inserted in either the flags collection or the segments collection.
    FullDataSet<SerializedItemDescriptor> goodData = makeGoodData();
    List<Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>>> dataPlusBadItem =
        Lists.newArrayList(goodData.getData()); // converting the data set to a mutable list
    List<Map.Entry<String, SerializedItemDescriptor>> items = Lists.newArrayList(
        dataPlusBadItem.get(collIndex).getValue().getItems()); // this is now either list of flags or list of segments
    items.add(0, new SimpleEntry<>(BAD_ITEM_KEY, badItem));
    // putting the bad item first to prove that items after that one are still stored
    dataPlusBadItem.set(collIndex, new SimpleEntry<>(dataKind, new KeyedItems<>(items)));
    
    // Initialize the store with this data set. It should not throw an exception, but instead just
    // log an error and store all the *other* items-- so the resulting state should be the same as
    // makeGoodData().
    try (PersistentDataStore store = buildStore(null).build(makeClientContext())) {
      store.init(new FullDataSet<>(dataPlusBadItem));

      assertDataSetsEqual(goodData, getAllData(store));
    }
  }
  
  private void dataStoreSkipsAndLogsTooLargeItemOnUpsert(
      DataKind dataKind
      ) throws Exception {
    FullDataSet<SerializedItemDescriptor> goodData = makeGoodData();
    
    // Initialize the store with valid data. 
    try (PersistentDataStore store = buildStore(null).build(makeClientContext())) {
      store.init(goodData);
      
      assertDataSetsEqual(goodData, getAllData(store));
      
      // Now try to store an oversized item. It should not throw an exception, but should not do
      // the update-- so the resulting state should be the same valid data as before.
      store.upsert(dataKind, BAD_ITEM_KEY, makeTooBigItem(dataKind));

      assertDataSetsEqual(goodData, getAllData(store));
    }
  }
  
  private ClientContext makeClientContext() {
    return clientContext("", baseConfig().build());
  }

  private static FullDataSet<SerializedItemDescriptor> makeGoodData() {
    return new FullDataSet<SerializedItemDescriptor>(ImmutableList.of(
         new SimpleEntry<>(DataModel.FEATURES,
             new KeyedItems<SerializedItemDescriptor>(ImmutableList.of(
                 new SimpleEntry<>("flag1",
                     new SerializedItemDescriptor(1, false, "{\"key\": \"flag1\", \"version\": 1}")),
                 new SimpleEntry<>("flag2",
                     new SerializedItemDescriptor(1, false, "{\"key\": \"flag2\", \"version\": 1}"))
                 ))),
         new SimpleEntry<>(DataModel.SEGMENTS,
             new KeyedItems<SerializedItemDescriptor>(ImmutableList.of(
                 new SimpleEntry<>("segment1",
                     new SerializedItemDescriptor(1, false, "{\"key\": \"segment1\", \"version\": 1}")),
                 new SimpleEntry<>("segment2",
                     new SerializedItemDescriptor(1, false, "{\"key\": \"segment2\", \"version\": 1}"))
                 )))
         ));
  }

  private static SerializedItemDescriptor makeTooBigItem(DataKind dataKind) {
    StringBuilder tooBigKeysListJson = new StringBuilder().append('[');
    for (int i = 0; i < 40000; i++) {
      if (i != 0 ) {
        tooBigKeysListJson.append(',');
      }
      tooBigKeysListJson.append("\"key").append(i).append('"');
    }
    tooBigKeysListJson.append(']');
    assertThat(tooBigKeysListJson.length(), greaterThan(400 * 1024));

    String serializedItem;
    if (dataKind == DataModel.SEGMENTS) {
      serializedItem = "{\":key\":\"" + BAD_ITEM_KEY + "\", \"version\": 1, \"included\": " +
          tooBigKeysListJson.toString() + "}";
    } else {
      serializedItem = "{\":key\":\"" + BAD_ITEM_KEY + "\", \"version\": 1, \"targets\": [{\"variation\": 0, \"values\":" +
        tooBigKeysListJson.toString() + "}]}";
    }
    return new SerializedItemDescriptor(1, false, serializedItem);
  }
  
  private static FullDataSet<SerializedItemDescriptor> getAllData(PersistentDataStore store) {
    List<Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>>> colls = new ArrayList<>();
    for (DataKind kind: new DataKind[] { DataModel.FEATURES, DataModel.SEGMENTS }) {
      KeyedItems<SerializedItemDescriptor> items = store.getAll(kind);
      colls.add(new SimpleEntry<>(kind, items));
    }
    return new FullDataSet<>(colls);
  }
  
  private static void assertDataSetsEqual(FullDataSet<SerializedItemDescriptor> expected,
      FullDataSet<SerializedItemDescriptor> actual) {
    if (!actual.equals(expected)) {
      throw new AssertionError("expected " + describeDataSet(expected) + " but got " +
          describeDataSet(actual));
    }
  }

  private static String describeDataSet(FullDataSet<SerializedItemDescriptor> data) {
    return Joiner.on(", ").join(
        Iterables.transform(data.getData(), entry -> {
          DataKind kind = entry.getKey();
          return "{" + kind + ": [" +
            Joiner.on(", ").join(
                Iterables.transform(entry.getValue().getItems(), item -> item.getValue().getSerializedItem())
                ) +
              "]}";
        }));
  }
}
