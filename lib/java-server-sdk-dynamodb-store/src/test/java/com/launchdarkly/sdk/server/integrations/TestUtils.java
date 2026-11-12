package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.subsystems.BigSegmentStore;
import com.launchdarkly.sdk.server.subsystems.PersistentDataStore;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.launchdarkly.sdk.server.integrations.CollectionHelpers.mapOf;
import static com.launchdarkly.sdk.server.integrations.DynamoDbStoreImplBase.PARTITION_KEY;
import static com.launchdarkly.sdk.server.integrations.DynamoDbStoreImplBase.SORT_KEY;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import software.amazon.awssdk.services.dynamodb.paginators.ScanIterable;

@SuppressWarnings("javadoc")
public class TestUtils {
  static final String TABLE_NAME = "LD_DYNAMODB_TEST_TABLE";
  static final URI DYNAMODB_ENDPOINT = URI.create("http://localhost:8000");
  private static DynamoDbClient client;

  static AwsCredentialsProvider getTestCredentials() {
    // The values here don't matter, it just expects us to provide something (since there may not be AWS
    // environment variables or config files where the tests are running)
    return StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret"));
  }

  static DynamoDbStoreBuilder<PersistentDataStore> baseDataStoreBuilder() {
    return DynamoDb.dataStore(TABLE_NAME)
        .endpoint(DYNAMODB_ENDPOINT)
        .region(Region.US_EAST_1)
        .credentials(getTestCredentials());
  }

  static DynamoDbStoreBuilder<BigSegmentStore> baseBigSegmentStoreBuilder() {
    return DynamoDb.bigSegmentStore(TABLE_NAME)
        .endpoint(DYNAMODB_ENDPOINT)
        .region(Region.US_EAST_1)
        .credentials(getTestCredentials());
  }

  static DynamoDbClient createTestClient() {
    if (client == null) {
      client = DynamoDbClient.builder()
          .endpointOverride(DYNAMODB_ENDPOINT)
          .region(Region.US_EAST_1)
          .credentialsProvider(getTestCredentials())
          .build();
    }
    return client;
  }

  static void createTableIfNecessary() {
    DynamoDbClient client = createTestClient();
    try {
      client.describeTable(DescribeTableRequest.builder().tableName(TABLE_NAME).build());
      return; // table exists
    } catch (ResourceNotFoundException e) {
      // fall through to code below - we'll create the table
    }

    CreateTableRequest request = CreateTableRequest.builder()
        .tableName(TABLE_NAME)
        .keySchema(
            KeySchemaElement.builder().attributeName(PARTITION_KEY).keyType(KeyType.HASH).build(),
            KeySchemaElement.builder().attributeName(SORT_KEY).keyType(KeyType.RANGE).build()
        ).attributeDefinitions(
            AttributeDefinition.builder().attributeName(PARTITION_KEY).attributeType(ScalarAttributeType.S).build(),
            AttributeDefinition.builder().attributeName(SORT_KEY).attributeType(ScalarAttributeType.S).build()
        ).provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(1L).writeCapacityUnits(1L).build())
        .build();

    client.createTable(request);
  }

  static void clearEverything(String prefix) {
    String keyPrefix = prefix == null ? "" : (prefix + ":");
    DynamoDbClient client = createTestClient();

    ScanIterable results = client.scanPaginator(builder -> builder.tableName(TABLE_NAME)
        .consistentRead(true)
        .projectionExpression("#namespace, #key")
        .expressionAttributeNames(mapOf(
            "#namespace", PARTITION_KEY,
            "#key", SORT_KEY)));

    List<Map<String, AttributeValue>> itemsToDelete = new ArrayList<>();
    for (ScanResponse resp: results) {
      for (Map<String, AttributeValue> item: resp.items()) {
        if (item.get(PARTITION_KEY).s().startsWith(keyPrefix)) {
          itemsToDelete.add(item);
        }
      }
    }

    List<WriteRequest> requests = new ArrayList<>();
    for (Map<String, AttributeValue> item: itemsToDelete) {
      requests.add(WriteRequest.builder().deleteRequest(builder -> builder.key(item)).build());
    }

    DynamoDbDataStoreImpl.batchWriteRequests(client, TABLE_NAME, requests);
  }
}
