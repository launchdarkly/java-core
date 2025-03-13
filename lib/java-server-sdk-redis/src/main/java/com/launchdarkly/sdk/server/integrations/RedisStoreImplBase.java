package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;

import java.io.Closeable;
import java.io.IOException;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

abstract class RedisStoreImplBase implements Closeable {
  protected final LDLogger logger;
  protected final JedisPool pool;
  protected final String prefix;

  protected RedisStoreImplBase(RedisStoreBuilder<?> builder, LDLogger logger) {
    this.logger = logger;

    // There is no builder for JedisPool, just a large number of constructor overloads. Unfortunately,
    // the overloads that accept a URI do not accept the other parameters we need to set, so we need
    // to decompose the URI.
    String host = builder.uri.getHost();
    int port = builder.uri.getPort();
    String password = builder.password == null ? RedisURIComponents.getPassword(builder.uri) : builder.password;
    int database = builder.database == null ? RedisURIComponents.getDBIndex(builder.uri) : builder.database;
    boolean tls = builder.tls || builder.uri.getScheme().equals("rediss");

    String extra = tls ? " with TLS" : "";
    if (password != null) {
      extra = extra + (extra.isEmpty() ? " with" : " and") + " password";
    }
    logger.info("Using Redis data store at {}:{}/{}{}", host, port, database, extra);

    JedisPoolConfig poolConfig = (builder.poolConfig != null) ? builder.poolConfig : new JedisPoolConfig();

    this.prefix = (builder.prefix == null || builder.prefix.isEmpty()) ?
        RedisStoreBuilder.DEFAULT_PREFIX :
        builder.prefix;
    this.pool = new JedisPool(poolConfig,
        host,
        port,
        (int) builder.connectTimeout.toMillis(),
        (int) builder.socketTimeout.toMillis(),
        password,
        database,
        null, // clientName
        tls,
        null, // sslSocketFactory
        null, // sslParameters
        null  // hostnameVerifier
    );
  }

  @Override
  public void close() throws IOException {
    logger.info("Closing Redis store");
    pool.destroy();
  }
}
