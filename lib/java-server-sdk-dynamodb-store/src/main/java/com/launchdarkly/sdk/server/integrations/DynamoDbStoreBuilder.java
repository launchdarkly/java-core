package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.Components;
import com.launchdarkly.sdk.server.subsystems.BigSegmentStore;
import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.DiagnosticDescription;
import com.launchdarkly.sdk.server.subsystems.PersistentDataStore;

import java.net.URI;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

/**
 * A <a href="http://en.wikipedia.org/wiki/Builder_pattern">builder</a> for configuring the
 * DynamoDB-based persistent data store and/or Big Segment store.
 * <p>
 * Both {@link DynamoDb#dataStore(String)} and {@link DynamoDb#bigSegmentStore(String)} return instances of
 * this class. You can use methods of the builder to specify any non-default DynamoDB options
 * you may want, before passing the builder to either {@link Components#persistentDataStore(ComponentConfigurer)}
 * or {@link Components#bigSegments(ComponentConfigurer)} as appropriate. The two types of
 * stores are independent of each other; you do not need a Big Segment store if you are not
 * using the Big Segments feature, and you do not need to use the same database for both.
 *
 * In this example, the main data store uses a DynamoDB table called "table1", and the Big
 * Segment store uses a DynamoDB table called "table2":
 * <pre><code>
 *     LDConfig config = new LDConfig.Builder()
 *         .dataStore(
 *             Components.persistentDataStore(
 *                 DynamoDb.dataStore("table1")
 *             )
 *         )
 *         .bigSegments(
 *             Components.bigSegments(
 *                 DynamoDb.dataStore("table2")
 *             )
 *         )
 *         .build();
 * </code></pre>
 * <p>
 * Note that the SDK also has its own options related to data storage that are configured
 * at a different level, because they are independent of what database is being used. For
 * instance, the builder returned by {@link Components#persistentDataStore(ComponentConfigurer)}
 * has options for caching:
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
 * @param <T> the component type that this builder is being used for 
 * <p>
 * The AWS SDK provides many configuration options for a DynamoDB client. This class has
 * corresponding methods for some of the most commonly used ones. If you need more sophisticated
 * control over the DynamoDB client, you can construct one of your own and pass it in with the
 * {@link #existingClient(DynamoDbClient)} method.
 *
 * @since 2.1.0
 */
public abstract class DynamoDbStoreBuilder<T> implements ComponentConfigurer<T>, DiagnosticDescription {
  final String tableName;
  
  String prefix;
  DynamoDbClient existingClient;
  DynamoDbClientBuilder clientBuilder;
  
  private DynamoDbStoreBuilder(String tableName) {
    this.tableName = tableName;
    clientBuilder = DynamoDbClient.builder();
  }
  
  /**
   * Sets the main AWS client configuration options for the DynamoDB client.
   * 
   * @param config an AWS client configuration object
   * @return the builder
   */
  public DynamoDbStoreBuilder<T> clientOverrideConfiguration(ClientOverrideConfiguration config) {
    clientBuilder.overrideConfiguration(config);
    return this;
  }
  
  /**
   * Sets the AWS client credentials. If you do not set them programmatically, the AWS SDK will
   * attempt to find them in environment variables and/or local configuration files.
   *
   * @param credentialsProvider a source of credentials
   * @return the builder
   */
  public DynamoDbStoreBuilder<T> credentials(AwsCredentialsProvider credentialsProvider) {
    clientBuilder.credentialsProvider(credentialsProvider);
    return this;
  }
  
  /**
   * Sets the service endpoint to use. Normally, you will not use this, as AWS determines the
   * service endpoint based on your region. However, you can set it explicitly if you are
   * running your own DynamoDB instance.
   * 
   * @param endpointUri the custom endpoint URI
   * @return the builder
   */
  public DynamoDbStoreBuilder<T> endpoint(URI endpointUri) {
    clientBuilder.endpointOverride(endpointUri);
    return this;
  }
  
  /**
   * Sets the AWS region to use. If you do not set this, AWS will attempt to determine it from
   * environment variables and/or local configuration files.
   * 
   * @param region the AWS region
   * @return the builder
   */
  public DynamoDbStoreBuilder<T> region(Region region) {
    clientBuilder.region(region);
    return this;
  }

  /**
   * Sets an optional namespace prefix for all keys stored in DynamoDB. Use this if you are sharing
   * the same database table between multiple clients that are for different LaunchDarkly
   * environments, to avoid key collisions. 
   *
   * @param prefix the namespace prefix
   * @return the builder
   */
  public DynamoDbStoreBuilder<T> prefix(String prefix) {
    this.prefix = prefix;
    return this;
  }

  /**
   * Specifies an existing, already-configured DynamoDB client instance that the data store
   * should use rather than creating one of its own. If you specify an existing client, then the
   * other builder methods for configuring DynamoDB are ignored.
   *
   * @param existingClient an existing DynamoDB client instance
   * @return the builder
   */
  public DynamoDbStoreBuilder<T> existingClient(DynamoDbClient existingClient) {
    this.existingClient = existingClient;
    return this;
  }

  @Override
  public LDValue describeConfiguration(ClientContext context) {
    return LDValue.of("DynamoDB");
  }
  
  DynamoDbClient makeClient() {
    return existingClient != null ? existingClient : clientBuilder.build();
  }
  
  final static class ForDataStore extends DynamoDbStoreBuilder<PersistentDataStore> {
    ForDataStore(String tableName) {
      super(tableName);
    }

    @Override
    public PersistentDataStore build(ClientContext clientContext) {
      return new DynamoDbDataStoreImpl(existingClient != null ? existingClient : clientBuilder.build(),
          existingClient != null, tableName, prefix, clientContext.getBaseLogger());
    }
  }
  
  final static class ForBigSegments extends DynamoDbStoreBuilder<BigSegmentStore> {
    ForBigSegments(String tableName) {
      super(tableName);
    }

    @Override
    public BigSegmentStore build(ClientContext clientContext) {
      return new DynamoDbBigSegmentStoreImpl(existingClient != null ? existingClient : clientBuilder.build(),
          existingClient != null, tableName, prefix, clientContext.getBaseLogger());
    }
  }
}
