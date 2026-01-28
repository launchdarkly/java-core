package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.Components;
import com.launchdarkly.sdk.server.subsystems.BigSegmentStore;
import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.DiagnosticDescription;
import com.launchdarkly.sdk.server.subsystems.PersistentDataStore;

import java.net.URI;
import java.time.Duration;

import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

/**
 * A <a href="http://en.wikipedia.org/wiki/Builder_pattern">builder</a> for configuring the
 * Redis-based persistent data store and/or Big Segment store.
 * <p>
 * Both {@link Redis#dataStore()} and {@link Redis#bigSegmentStore()} return instances of
 * this class. You can use methods of the builder to specify any non-default Redis options
 * you may want, before passing the builder to either {@link Components#persistentDataStore(ComponentConfigurer)}
 * or {@link Components#bigSegments(ComponentConfigurer)} as appropriate. The two types of
 * stores are independent of each other; you do not need a Big Segment store if you are not
 * using the Big Segments feature, and you do not need to use the same database for both.
 *
 * In this example, the main data store uses a Redis host called "host1", and the Big Segment
 * store uses a Redis host called "host2":
 * <pre><code>
 *     LDConfig config = new LDConfig.Builder()
 *         .dataStore(
 *             Components.persistentDataStore(
 *                 Redis.dataStore().uri(URI.create("redis://host1:6379")
 *             )
 *         )
 *         .bigSegments(
 *             Components.bigSegments(
 *                 Redis.dataStore().uri(URI.create("redis://host2:6379")
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
 * @param <T> the component type that this builder is being used for 
 *
 * @since 5.0.0
 */
public abstract class RedisStoreBuilder<T> implements ComponentConfigurer<T>, DiagnosticDescription {
  /**
   * The default value for the Redis URI: {@code redis://localhost:6379}
   */
  public static final URI DEFAULT_URI = URI.create("redis://localhost:6379");
  
  /**
   * The default value for {@link #prefix(String)}.
   */
  public static final String DEFAULT_PREFIX = "launchdarkly";
  
  URI uri = DEFAULT_URI;
  String prefix = DEFAULT_PREFIX;
  Duration connectTimeout = Duration.ofMillis(Protocol.DEFAULT_TIMEOUT);
  Duration socketTimeout = Duration.ofMillis(Protocol.DEFAULT_TIMEOUT);
  Integer database = null;
  String username = null;
  String password = null;
  boolean tls = false;
  JedisPoolConfig poolConfig = null;

  // These constructors are called only from Implementations
  RedisStoreBuilder() {
  }
  
  /**
   * Specifies the database number to use.
   * <p>
   * The database number can also be specified in the Redis URI, in the form {@code redis://host:port/NUMBER}. Any
   * non-null value that you set with {@link #database(Integer)} will override the URI.
   * 
   * @param database the database number, or null to fall back to the URI or the default
   * @return the builder
   */
  public RedisStoreBuilder<T> database(Integer database) {
    this.database = database;
    return this;
  }
  
  /**
   * Specifies a username for Redis ACL authentication.
   * <p>
   * Redis 6.0+ supports Access Control Lists (ACL) with username/password authentication.
   * It is also possible to include a username in the Redis URI, in the form {@code redis://USERNAME:PASSWORD@host:port}.
   * Any username that you set with {@link #username(String)} will override the URI.
   * <p>
   * Note: Using this feature requires Jedis 3.6.0 or later.
   * 
   * @param username the username for ACL authentication
   * @return the builder
   * @since 2.2.0
   */
  public RedisStoreBuilder<T> username(String username) {
    this.username = username;
    return this;
  }
  
  /**
   * Specifies a password that will be sent to Redis in an AUTH command.
   * <p>
   * It is also possible to include a password in the Redis URI, in the form {@code redis://:PASSWORD@host:port}
   * or {@code redis://USERNAME:PASSWORD@host:port} for ACL authentication. Any password that you set with
   * {@link #password(String)} will override the URI.
   * 
   * @param password the password
   * @return the builder
   */
  public RedisStoreBuilder<T> password(String password) {
    this.password = password;
    return this;
  }
  
  /**
   * Optionally enables TLS for secure connections to Redis.
   * <p>
   * This is equivalent to specifying a Redis URI that begins with {@code rediss:} rather than {@code redis:}.
   * <p>
   * Note that not all Redis server distributions support TLS.
   * 
   * @param tls true to enable TLS
   * @return the builder
   */
  public RedisStoreBuilder<T> tls(boolean tls) {
    this.tls = tls;
    return this;
  }
  
  /**
   * Specifies a Redis host URI other than {@link #DEFAULT_URI}.
   * 
   * @param redisUri the URI of the Redis host
   * @return the builder
   */
  public RedisStoreBuilder<T> uri(URI redisUri) {
    this.uri = redisUri;
    return this;
  }
    
  /**
   * Optionally configures the namespace prefix for all keys stored in Redis.
   *
   * @param prefix the namespace prefix
   * @return the builder
   */
  public RedisStoreBuilder<T> prefix(String prefix) {
    this.prefix = prefix;
    return this;
  }

  /**
   * Optional override if you wish to specify your own configuration to the underlying Jedis pool.
   *
   * @param poolConfig the Jedis pool configuration.
   * @return the builder
   */
  public RedisStoreBuilder<T> poolConfig(JedisPoolConfig poolConfig) {
    this.poolConfig = poolConfig;
    return this;
  }

  /**
   * Optional override which sets the connection timeout for the underlying Jedis pool which otherwise defaults to
   * {@link redis.clients.jedis.Protocol#DEFAULT_TIMEOUT} milliseconds.
   *
   * @param connectTimeout the timeout
   * @return the builder
   */
  public RedisStoreBuilder<T> connectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout == null ? Duration.ofMillis(Protocol.DEFAULT_TIMEOUT) : connectTimeout;
    return this;
  }

  /**
   * Optional override which sets the connection timeout for the underlying Jedis pool which otherwise defaults to
   * {@link redis.clients.jedis.Protocol#DEFAULT_TIMEOUT} milliseconds.
   *
   * @param socketTimeout the socket timeout
   * @return the builder
   */
  public RedisStoreBuilder<T> socketTimeout(Duration socketTimeout) {
    this.socketTimeout = socketTimeout == null ? Duration.ofMillis(Protocol.DEFAULT_TIMEOUT) : socketTimeout;
    return this;
  }

  @Override
  public LDValue describeConfiguration(ClientContext clientContext) {
    return LDValue.of("Redis");
  }
  
  static final class ForDataStore extends RedisStoreBuilder<PersistentDataStore> {
    @Override
    public PersistentDataStore build(ClientContext clientContext) {
      return new RedisDataStoreImpl(this, clientContext.getBaseLogger());
    }
  }
  
  static final class ForBigSegments extends RedisStoreBuilder<BigSegmentStore> {
    @Override
    public BigSegmentStore build(ClientContext clientContext) {
      return new RedisBigSegmentStoreImpl(this, clientContext.getBaseLogger());
    }
  }
}
