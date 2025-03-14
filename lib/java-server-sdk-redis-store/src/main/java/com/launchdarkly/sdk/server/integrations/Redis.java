package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.Components;
import com.launchdarkly.sdk.server.subsystems.BigSegmentStore;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.PersistentDataStore;

/**
 * Integration between the LaunchDarkly SDK and Redis.
 * 
 * @since 4.12.0
 */
public abstract class Redis {
  /**
   * Returns a builder object for creating a Redis-backed persistent data store.
   * <p>
   * This is for the main data store that holds feature flag data. To configure a
   * Big Segment store, use {@link #bigSegmentStore()} instead.
   * <p>
   * You can use methods of the builder to specify any non-default Redis options
   * you may want, before passing the builder to {@link Components#persistentDataStore(ComponentConfigurer)}.
   * In this example, the store is configured to use a Redis host called "host1":
   * <pre><code>
   *     LDConfig config = new LDConfig.Builder()
   *         .dataStore(
   *             Components.persistentDataStore(
   *                 Redis.dataStore().uri(URI.create("redis://host1:6379")
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
   *                 Redis.dataStore().uri(URI.create("redis://my-redis-host"))
   *             ).cacheSeconds(15)
   *         )
   *         .build();
   * </code></pre>
   *
   * @return a data store configuration object
   */
  public static RedisStoreBuilder<PersistentDataStore> dataStore() {
    return new RedisStoreBuilder.ForDataStore();
  }

  /**
   * Returns a builder object for creating a Redis-backed Big Segment store.
   * <p>
   * You can use methods of the builder to specify any non-default Redis options
   * you may want, before passing the builder to {@link Components#bigSegments(ComponentConfigurer)}.
   * In this example, the store is configured to use a Redis host called "host2":
   * <pre><code>
   *     LDConfig config = new LDConfig.Builder()
   *         .bigSegments(
   *             Components.bigSegments(
   *                 Redis.bigSegmentStore().uri(URI.create("redis://host2:6379")
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
   *                 Redis.bigSegmentStore().uri(URI.create("redis://my-redis-host"))
   *             ).statusPollInterval(Duration.ofSeconds(30))
   *         )
   *         .build();
   * </code></pre>
   * 
   * @return a Big Segment store configuration object
   * @since 3.0.0
   */
  public static RedisStoreBuilder<BigSegmentStore> bigSegmentStore() {
    return new RedisStoreBuilder.ForBigSegments();
  }
  
  private Redis() {}
}
