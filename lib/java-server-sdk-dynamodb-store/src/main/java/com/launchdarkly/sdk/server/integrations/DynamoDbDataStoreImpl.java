package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.SerializedItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.PersistentDataStore;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.launchdarkly.sdk.server.integrations.CollectionHelpers.mapOf;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ComparisonOperator;
import software.amazon.awssdk.services.dynamodb.model.Condition;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;

/**
 * Internal implementation of the DynamoDB data store.
 * <p>
 * Implementation notes:
 * <ul>
 * 
 * <li> Feature flags, segments, and any other kind of entity the LaunchDarkly client may wish
 * to store, are all put in the same table. The only two required attributes are "key" (which
 * is present in all storeable entities) and "namespace" (a parameter from the client that is
 * used to disambiguate between flags and segments).
 *
 * <li> Because of DynamoDB's restrictions on attribute values (e.g. empty strings are not
 * allowed), the standard DynamoDB marshaling mechanism with one attribute per object property
 * is not used. Instead, the entire object is serialized to JSON and stored in a single
 * attribute, "item". The "version" property is also stored as a separate attribute since it
 * is used for updates.
 *
 * <li> Since DynamoDB doesn't have transactions, the init() method - which replaces the entire data
 * store - is not atomic, so there can be a race condition if another process is adding new data
 * via upsert(). To minimize this, we don't delete all the data at the start; instead, we update
 * the items we've received, and then delete all other items. That could potentially result in
 * deleting new data from another process, but that would be the case anyway if the init()
 * happened to execute later than the upsert(); we are relying on the fact that normally the
 * process that did the init() will also receive the new data shortly and do its own upsert().
 *
 * <li> DynamoDB has a maximum item size of 400KB. Since each feature flag or user segment is
 * stored as a single item, this mechanism will not work for extremely large flags or segments.
 * </ul>
 */
final class DynamoDbDataStoreImpl extends DynamoDbStoreImplBase implements PersistentDataStore {
  private static final String versionAttribute = "version";
  private static final String itemJsonAttribute = "item";
  private static final String deletedItemPlaceholder = "null"; // DynamoDB doesn't allow empty strings

  // We won't try to store items whose total size exceeds this. The DynamoDB documentation says
  // only "400KB", which probably means 400*1024, but to avoid any chance of trying to store a
  // too-large item we are rounding it down.
  private static final int DYNAMO_DB_MAX_ITEM_SIZE = 400000;

  private Runnable updateHook;
  
  DynamoDbDataStoreImpl(
    DynamoDbClient client,
    boolean wasExistingClient,
    String tableName,
    String prefix,
    LDLogger baseLogger
    ) {
    super(client, wasExistingClient, tableName, prefix,
      baseLogger.subLogger("DataStore").subLogger("DynamoDb"));
  }

  @Override
  public SerializedItemDescriptor get(DataKind kind, String key) {
    GetItemResponse resp = getItemByKeys(namespaceForKind(kind), key);
    return unmarshalItem(kind, resp.item());
  }

  @Override
  public KeyedItems<SerializedItemDescriptor> getAll(DataKind kind) {
    List<Map.Entry<String, SerializedItemDescriptor>> itemsOut = new ArrayList<>();
    for (QueryResponse resp: client.queryPaginator(makeQueryForKind(kind).build())) {
      for (Map<String, AttributeValue> item: resp.items()) {
        AttributeValue keyAttr = item.get(SORT_KEY);
        if (keyAttr != null && keyAttr.s() != null) {
          SerializedItemDescriptor itemOut = unmarshalItem(kind, item);
          if (itemOut != null) {
            itemsOut.add(new AbstractMap.SimpleEntry<>(keyAttr.s(), itemOut));
          }
        }
      }
    }
    return new KeyedItems<>(itemsOut);
  }

  @Override
  public void init(FullDataSet<SerializedItemDescriptor> allData) {
    // Start by reading the existing keys; we will later delete any of these that weren't in allData.
    Set<Map.Entry<String, String>> unusedOldKeys = readExistingKeys(allData);
    
    List<WriteRequest> requests = new ArrayList<>();
    int numItems = 0;
    
    // Insert or update every provided item
    for (Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>> entry: allData.getData()) {
      DataKind kind = entry.getKey();
      for (Map.Entry<String, SerializedItemDescriptor> itemEntry: entry.getValue().getItems()) {
        String key = itemEntry.getKey();
        Map<String, AttributeValue> encodedItem = marshalItem(kind, key, itemEntry.getValue());
        
        if (!checkSizeLimit(encodedItem)) {
          continue;
        }
        
        requests.add(WriteRequest.builder().putRequest(builder -> builder.item(encodedItem)).build());
        
        Map.Entry<String, String> combinedKey = new AbstractMap.SimpleEntry<>(namespaceForKind(kind), key);
        unusedOldKeys.remove(combinedKey);
        
        numItems++;
      }
    }
    
    // Now delete any previously existing items whose keys were not in the current data
    for (Map.Entry<String, String> combinedKey: unusedOldKeys) {
      if (!combinedKey.getKey().equals(initedKey())) {
        requests.add(WriteRequest.builder()
            .deleteRequest(builder ->
                builder.key(makeKeysMap(combinedKey.getKey(), combinedKey.getValue())))
            .build());
      }
    }
    
    // Now set the special key that we check in initializedInternal()
    requests.add(WriteRequest.builder()
        .putRequest(builder -> builder.item(makeKeysMap(initedKey(), initedKey())))
        .build());
    
    batchWriteRequests(client, tableName, requests);
    
    logger.info("Initialized table {} with {} items", tableName, numItems);
  }

  @Override
  public boolean upsert(DataKind kind, String key, SerializedItemDescriptor newItem) {
    Map<String, AttributeValue> encodedItem = marshalItem(kind, key, newItem);
    if (!checkSizeLimit(encodedItem)) {
      return false;
    }
    
    if (updateHook != null) { // instrumentation for tests
      updateHook.run();
    }
    
    try {
      client.putItem(builder -> builder.tableName(tableName)
          .item(encodedItem)
          .conditionExpression("attribute_not_exists(#namespace) or attribute_not_exists(#key) or :version > #version")
          .expressionAttributeNames(mapOf(
              "#namespace", PARTITION_KEY,
              "#key", SORT_KEY,
              "#version", versionAttribute))
          .expressionAttributeValues(mapOf(
              ":version", AttributeValue.builder().n(String.valueOf(newItem.getVersion())).build()))
      );
    } catch (ConditionalCheckFailedException e) {
      // The item was not updated because there's a newer item in the database.
      return false;
    }
    
    return true;
  }

  @Override
  public boolean isInitialized() {
    GetItemResponse resp = getItemByKeys(initedKey(), initedKey());
    return resp.item() != null && resp.item().size() > 0;
  }

  //@Override
  public boolean isStoreAvailable() {
    try {
      isInitialized(); // don't care about the return value, just that it doesn't throw an exception
      return true;
    } catch (Exception e) { // don't care about exception class, since any exception means the DynamoDB request couldn't be made
      return false;
    }
  }
  
  public void setUpdateHook(Runnable updateHook) {
    this.updateHook = updateHook;
  }

  private String namespaceForKind(DataKind kind) {
    return prefixedNamespace(kind.getName());
  }
  
  private String initedKey() {
    return prefixedNamespace("$inited");
  }

  private QueryRequest.Builder makeQueryForKind(DataKind kind) {
    Map<String, Condition> keyConditions = mapOf(
        PARTITION_KEY,
        Condition.builder()
          .comparisonOperator(ComparisonOperator.EQ)
          .attributeValueList(AttributeValue.builder().s(namespaceForKind(kind)).build())
          .build()
    );
    return QueryRequest.builder()
        .tableName(tableName)
        .consistentRead(true)
        .keyConditions(keyConditions);
  }
  
  private Set<Map.Entry<String, String>> readExistingKeys(FullDataSet<?> kindsFromThisDataSet) {
    Set<Map.Entry<String, String>> keys = new HashSet<>();
    for (Map.Entry<DataKind, ?> e: kindsFromThisDataSet.getData()) {
      DataKind kind = e.getKey();
      QueryRequest req = makeQueryForKind(kind)
        .projectionExpression("#namespace, #key")
        .expressionAttributeNames(mapOf(
            "#namespace", PARTITION_KEY, "#key", SORT_KEY))
        .build();
      QueryIterable queryResults = client.queryPaginator(req);
      for (QueryResponse resp: queryResults) {
        for (Map<String, AttributeValue> item: resp.items()) {
          String namespace = item.get(PARTITION_KEY).s();
          String key = item.get(SORT_KEY).s();
          keys.add(new AbstractMap.SimpleEntry<>(namespace, key));
        }
      }
    }
    return keys;
  }
  
  private Map<String, AttributeValue> marshalItem(DataKind kind, String key, SerializedItemDescriptor item) {
    String json = item.isDeleted() ? deletedItemPlaceholder : item.getSerializedItem();
    return mapOf(
        PARTITION_KEY, AttributeValue.builder().s(namespaceForKind(kind)).build(),
        SORT_KEY, AttributeValue.builder().s(key).build(),
        versionAttribute, AttributeValue.builder().n(String.valueOf(item.getVersion())).build(),
        itemJsonAttribute, AttributeValue.builder().s(json).build()
    );
  }
  
  private SerializedItemDescriptor unmarshalItem(DataKind kind, Map<String, AttributeValue> item) {
    if (item == null || item.size() == 0) {
      return null;
    }
    AttributeValue jsonAttr = item.get(itemJsonAttribute);
    if (jsonAttr == null || jsonAttr.s() == null) {
      throw new IllegalStateException("DynamoDB map did not contain expected item string");
    }
    String jsonValue = jsonAttr.s();
    AttributeValue versionAttr = item.get(versionAttribute);
    if (versionAttr == null || versionAttr.n() == null) {
      throw new IllegalStateException("DynamoDB map did not contain expected version attribute");
    }
    int version;
    try {
      version = Integer.parseInt(versionAttr.n());
    } catch (NumberFormatException e) {
      throw new IllegalStateException("DynamoDB version attribute had a non-numeric value");
    }
    if (jsonValue.equals(deletedItemPlaceholder)) {
      return new SerializedItemDescriptor(version, true, null);
    }
    return new SerializedItemDescriptor(version, false, jsonValue);
  }
  
  static void batchWriteRequests(DynamoDbClient client, String tableName, List<WriteRequest> requests) {
    int batchSize = 25;
    for (int i = 0; i < requests.size(); i += batchSize) {
      int limit = (i + batchSize < requests.size()) ? (i + batchSize) : requests.size();
      List<WriteRequest> batch = requests.subList(i, limit); 
      Map<String, List<WriteRequest>> batchMap = mapOf(tableName, batch);
      client.batchWriteItem(builder -> builder.requestItems(batchMap));
    }
  }
  
  private boolean checkSizeLimit(Map<String, AttributeValue> item) {
    // see: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/CapacityUnitCalculations.html
    int size = 100; // fixed overhead for index data
    for (Map.Entry<String, AttributeValue> kv: item.entrySet()) {
      size += utf8Length(kv.getKey());
      if (kv.getValue().s() != null) {
        size += utf8Length(kv.getValue().s());
      } else if (kv.getValue().n() != null) {
        size += utf8Length(kv.getValue().n());
      }
    }
    if (size <= DYNAMO_DB_MAX_ITEM_SIZE) {
      return true;
    }
    logger.error("The item \"{}\" in \"{}\" was too large to store in DynamoDB and was dropped",
        item.get(SORT_KEY).s(), item.get(PARTITION_KEY).s());
    return false;
  }
  
  private static int utf8Length(String s) {
    // Unfortunately Java (at least Java 8) doesn't have a built-in way to determine the UTF8 encoding
    // length without actually creating a new byte array, which we would rather not do. Guava does
    // support this, but we don't want a dependency on Guava (except in test code).
    if (s == null) {
      return 0;
    }
    int count = 0;
    for (int i = 0, len = s.length(); i < len; i++) {
      char ch = s.charAt(i);
      if (ch <= 0x7F) {
        count++;
      } else if (ch <= 0x7FF) {
        count += 2;
      } else if (Character.isHighSurrogate(ch)) {
        count += 4;
        ++i;
      } else {
        count += 3;
      }
    }
    return count;
  }
}
