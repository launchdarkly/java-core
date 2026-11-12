package com.launchdarkly.sdk.server.integrations;

import static com.launchdarkly.sdk.server.integrations.CollectionHelpers.mapOf;

import com.launchdarkly.logging.LDLogger;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

abstract class DynamoDbStoreImplBase implements Closeable {
  static final String PARTITION_KEY = "namespace";
  static final String SORT_KEY = "key";

  protected final DynamoDbClient client;
  protected final boolean wasExistingClient;
  protected final String tableName;
  protected final String prefix;
  protected final LDLogger logger;

  public DynamoDbStoreImplBase(
    DynamoDbClient client,
    boolean wasExistingClient,
    String tableName,
    String prefix,
    LDLogger logger
    ) {
    this.client = client;
    this.wasExistingClient = wasExistingClient;
    this.tableName = tableName;
    this.prefix = "".equals(prefix) ? null : prefix;
    this.logger = logger;
  }

  protected String prefixedNamespace(String base) {
    return prefix == null ? base : (prefix + ":" + base);
  }

  protected Map<String, AttributeValue> makeKeysMap(String partitionKey, String sortKey) {
    return mapOf(
        PARTITION_KEY, AttributeValue.builder().s(partitionKey).build(),
        SORT_KEY, AttributeValue.builder().s(sortKey).build());
  }

  protected GetItemResponse getItemByKeys(String namespace, String key) {
    return client.getItem(builder -> builder.tableName(tableName)
        .consistentRead(true)
        .key(makeKeysMap(namespace, key))
    );
  }
  public void close() throws IOException {
    if (!wasExistingClient) {
      client.close();
    }
  }
}
