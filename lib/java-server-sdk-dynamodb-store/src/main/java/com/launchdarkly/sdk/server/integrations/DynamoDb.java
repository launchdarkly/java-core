package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.Components;
import com.launchdarkly.sdk.server.subsystems.BigSegmentStore;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.PersistentDataStore;

/**
 * Integration between the LaunchDarkly SDK and DynamoDB.
 *
 * @since 2.1.0
 */
public abstract class DynamoDb {
  /**
   * Returns a builder object for creating a DynamoDB-backed data store.
   * <p>
   * This is for the main data store that holds feature flag data. To configure a
   * Big Segment store, use {@link #bigSegmentStore(String)} instead.
   * <p>
   * You can use methods of the builder to specify any non-default DynamoDB options
   * you may want, before passing the builder to {@link Components#persistentDataStore(ComponentConfigurer)}.
   * In this example, the data store uses a table called "table1" and a namespace prefix of "prefix1":
   *
   * <pre><code>
   *   LDConfig config = new LDConfig.Builder()
   *       .dataStore(
   *           Components.persistentDataStore(
   *               DynamoDb.dataStore("table1").prefix("prefix1")
   *           )
   *       )
   *       .build();
   * </code></pre>
   *
   * Note that the SDK also has its own options related to data storage that are configured
   * at a different level, because they are independent of what database is being used. For
   * instance, the builder returned by {@link Components#persistentDataStore(ComponentConfigurer)}
   * has options for caching:
   *
   * <pre><code>
   *     LDConfig config = new LDConfig.Builder()
   *         .dataStore(
   *             Components.persistentDataStore(
   *                 DynamoDb.dataStore("table1")
   *             ).cacheSeconds(15)
   *         )
   *         .build();
   * </code></pre>
   * 
   * Note that the specified table must already exist in DynamoDB. It must have a partition key
   * of "namespace", and a sort key of "key".
   * <p>
   * By default, the data store uses a basic DynamoDB client configuration that takes its
   * AWS credentials and region from AWS environment variables and/or local configuration files.
   * There are options in the builder for changing some configuration options, or you can
   * configure the DynamoDB client yourself and pass it to the builder with
   * {@link DynamoDbStoreBuilder#existingClient(software.amazon.awssdk.services.dynamodb.DynamoDbClient)}.
   * <p>
   * If you are using the same DynamoDB table as a data store for multiple LaunchDarkly
   * environments, use the {@link DynamoDbStoreBuilder#prefix(String)} option and choose a 
   * different prefix string for each, so they will not interfere with each other's data.
   * 
   * @param tableName the table name in DynamoDB (must already exist)
   * @return a data store configuration object
   */
  public static DynamoDbStoreBuilder<PersistentDataStore> dataStore(String tableName) {
    return new DynamoDbStoreBuilder.ForDataStore(tableName);
  }

  /**
   * Returns a builder object for creating a DynamoDB-backed Big Segment store.
   * <p>
   * You can use methods of the builder to specify any non-default DynamoDB options
   * you may want, before passing the builder to {@link Components#bigSegments(ComponentConfigurer)}.
   * In this example, the store is configured to use a table called "table2" and a
   * namespace prefix of "prefix2":
   * <pre><code>
   *     LDConfig config = new LDConfig.Builder()
   *         .bigSegments(
   *             Components.bigSegments(
   *                 DynamoDb.bigSegmentStore("table2").prefix("prefix2")
   *             )
   *         )
   *         .build();
   * </code></pre>
   * <p>
   * Note that the SDK also has its own options related to Big Segments that are configured
   * at a different level, because they are independent of what database is being used. For
   * instance, the builder returned by {@link Components#bigSegments(ComponentConfigurer)}
   * has an option for the status polling interval: 
   * <pre><code>
   *     LDConfig config = new LDConfig.Builder()
   *         .dataStore(
   *             Components.bigSegments(
   *                 DynamoDb.bigSegmentStore("table2")
   *             ).statusPollInterval(Duration.ofSeconds(30))
   *         )
   *         .build();
   * </code></pre>
   * 
   * Note that the specified table must already exist in DynamoDB. It must have a partition key
   * of "namespace", and a sort key of "key".
   * 
   * @param tableName the table name in DynamoDB (must already exist)
   * @return a Big Segment store configuration object
   * @since 3.0.0
   */
  public static DynamoDbStoreBuilder<BigSegmentStore> bigSegmentStore(String tableName) {
    return new DynamoDbStoreBuilder.ForBigSegments(tableName);
  }
}
