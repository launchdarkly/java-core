package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.subsystems.BigSegmentStore;
import com.launchdarkly.sdk.server.subsystems.BigSegmentStoreTypes;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;

import org.junit.BeforeClass;

import static com.launchdarkly.sdk.server.integrations.CollectionHelpers.mapOf;
import static com.launchdarkly.sdk.server.integrations.DynamoDbStoreImplBase.PARTITION_KEY;
import static com.launchdarkly.sdk.server.integrations.DynamoDbStoreImplBase.SORT_KEY;
import static com.launchdarkly.sdk.server.integrations.TestUtils.TABLE_NAME;
import static com.launchdarkly.sdk.server.integrations.TestUtils.clearEverything;
import static com.launchdarkly.sdk.server.integrations.TestUtils.createTableIfNecessary;
import static com.launchdarkly.sdk.server.integrations.TestUtils.createTestClient;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Runs the standard database Big Segment store test suite that's defined in the Java SDK.
 * <p>
 * Note that you must be running a local DynamoDB instance on port 8000 to run these tests.
 * The simplest way to do this is:
 * <pre>
 *     docker run -p 8000:8000 amazon/dynamodb-local
 * </pre>
 */
@SuppressWarnings("javadoc")
public class DynamoDbBigSegmentStoreImplTest extends BigSegmentStoreTestBase {

  @BeforeClass
  public static void setUpAll() {
    createTableIfNecessary();
  }

  @Override
  protected ComponentConfigurer<BigSegmentStore> makeStore(String prefix) {
    return TestUtils.baseBigSegmentStoreBuilder().prefix(prefix);
  }

  @Override
  protected void clearData(String prefix) {
    clearEverything(prefix);
  }

  @Override
  protected void setMetadata(String prefix, BigSegmentStoreTypes.StoreMetadata metadata) {
    DynamoDbClient client = createTestClient();
    String key = prefix + ":big_segments_metadata";
    String syncTimeString = String.valueOf(metadata.getLastUpToDate());
    client.putItem(PutItemRequest.builder()
        .tableName(TABLE_NAME)
        .item(
            mapOf(
                PARTITION_KEY, AttributeValue.builder().s(key).build(),
                SORT_KEY, AttributeValue.builder().s(key).build(),
                "synchronizedOn", AttributeValue.builder().n(syncTimeString).build()
            )
        )
        .build());
  }

  @Override
  protected void setSegments(String prefix,
                             String userHashKey,
                             Iterable<String> includedSegmentRefs,
                             Iterable<String> excludedSegmentRefs) {
    DynamoDbClient client = createTestClient();
    if (includedSegmentRefs != null) {
      for (String includedRef: includedSegmentRefs) {
        addToSet(client, prefix, userHashKey, "included", includedRef);
      }
    }
    if (excludedSegmentRefs != null) {
      for (String excludedRef: excludedSegmentRefs) {
        addToSet(client, prefix, userHashKey, "excluded", excludedRef);
      }
    }
  }

  private void addToSet(DynamoDbClient client, String prefix, String userHash, String attrName, String value) {
    String namespaceKey = prefix + ":big_segments_user";
    client.updateItem(UpdateItemRequest.builder()
        .tableName(TABLE_NAME)
        .key(
            mapOf(
                PARTITION_KEY, AttributeValue.builder().s(namespaceKey).build(),
                SORT_KEY, AttributeValue.builder().s(userHash).build()
            )
        )
        .updateExpression(String.format("ADD %s :value", attrName))
        .expressionAttributeValues(mapOf(":value", AttributeValue.builder().ss(value).build()))
        .build());
  }
}
