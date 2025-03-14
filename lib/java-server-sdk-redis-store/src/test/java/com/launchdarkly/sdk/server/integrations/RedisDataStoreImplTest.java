package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.integrations.RedisDataStoreImpl.UpdateListener;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.PersistentDataStore;

import java.net.URI;

import redis.clients.jedis.Jedis;

@SuppressWarnings("javadoc")
public class RedisDataStoreImplTest extends PersistentDataStoreTestBase<RedisDataStoreImpl> {

  private static final URI REDIS_URI = URI.create("redis://localhost:6379");
  
  @Override
  protected ComponentConfigurer<PersistentDataStore> buildStore(String prefix) {
    return Redis.dataStore().uri(REDIS_URI).prefix(prefix);
  }
  
  @Override
  protected void clearAllData() {
    try (Jedis client = new Jedis("localhost")) {
      client.flushDB();
    }
  }
  
  @Override
  protected boolean setUpdateHook(RedisDataStoreImpl storeUnderTest, final Runnable hook) {
    storeUnderTest.setUpdateListener(new UpdateListener() {
      @Override
      public void aboutToUpdate(String baseKey, String itemKey) {
        hook.run();
      }
    });
    return true;
  }
}
