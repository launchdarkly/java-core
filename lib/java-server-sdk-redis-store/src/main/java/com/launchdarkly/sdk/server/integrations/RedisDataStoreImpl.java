package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.SerializedItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.PersistentDataStore;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

final class RedisDataStoreImpl extends RedisStoreImplBase implements PersistentDataStore {
  private UpdateListener updateListener;
  
  RedisDataStoreImpl(RedisStoreBuilder<PersistentDataStore> builder, LDLogger baseLogger) {
    super(builder, baseLogger.subLogger("DataStore").subLogger("Redis"));
  }
  
  @Override
  public SerializedItemDescriptor get(DataKind kind, String key) {
    try (Jedis jedis = pool.getResource()) {
      String item = getRedis(kind, key, jedis);
      return item == null ? null : new SerializedItemDescriptor(0, false, item);
    }
  }

  @Override
  public KeyedItems<SerializedItemDescriptor> getAll(DataKind kind) {
    try (Jedis jedis = pool.getResource()) {
      Map<String, String> allJson = jedis.hgetAll(itemsKey(kind));
      List<Map.Entry<String, SerializedItemDescriptor>> itemsOut = new ArrayList<>(allJson.size());
      for (Map.Entry<String, String> e: allJson.entrySet()) {
        itemsOut.add(new AbstractMap.SimpleEntry<>(e.getKey(), new SerializedItemDescriptor(0, false, e.getValue())));
      }
      return new KeyedItems<>(itemsOut);
    }
  }
  
  @Override
  public void init(FullDataSet<SerializedItemDescriptor> allData) {
    try (Jedis jedis = pool.getResource()) {
      Transaction t = jedis.multi();

      for (Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>> e0: allData.getData()) {
        DataKind kind = e0.getKey();
        String baseKey = itemsKey(kind); 
        t.del(baseKey);
        for (Map.Entry<String, SerializedItemDescriptor> e1: e0.getValue().getItems()) {
          t.hset(baseKey, e1.getKey(), jsonOrPlaceholder(kind, e1.getValue()));
        }
      }

      t.set(initedKey(), "");
      t.exec();
    }
  }
  
  @Override
  public boolean upsert(DataKind kind, String key, SerializedItemDescriptor newItem) {
    while (true) {
      Jedis jedis = null;
      try {
        jedis = pool.getResource();
        String baseKey = itemsKey(kind);
        jedis.watch(baseKey);
  
        if (updateListener != null) {
          updateListener.aboutToUpdate(baseKey, key);
        }
        
        String oldItemJson = getRedis(kind, key, jedis);
        // In this implementation, we have to parse the existing item in order to determine its version.
        int oldVersion = oldItemJson == null ? -1 : kind.deserialize(oldItemJson).getVersion();
  
        if (oldVersion >= newItem.getVersion()) {
          logger.debug("Attempted to {} key: {} version: {}" +
              " with a version that is the same or older: {} in \"{}\"",
              newItem.getSerializedItem() == null ? "delete" : "update",
              key, oldVersion, newItem.getVersion(), kind.getName());
          return false;
        }
  
        Transaction tx = jedis.multi();
        tx.hset(baseKey, key, jsonOrPlaceholder(kind, newItem));
        List<Object> result = tx.exec();
        if (result == null || result.isEmpty()) {
          // if exec failed, it means the watch was triggered and we should retry
          logger.debug("Concurrent modification detected, retrying");
          continue;
        }
  
        return true;
      } finally {
        if (jedis != null) {
          jedis.unwatch();
          jedis.close();
        }
      }
    }
  }
  
  @Override
  public boolean isInitialized() {
    try (Jedis jedis = pool.getResource()) {
      return jedis.exists(initedKey());
    }
  }
  
  @Override
  public boolean isStoreAvailable() {
    try {
      isInitialized(); // don't care about the return value, just that it doesn't throw an exception
      return true;
    } catch (Exception e) { // don't care about exception class, since any exception means the Redis request couldn't be made
      return false;
    }
  }

  // package-private for testing
  void setUpdateListener(UpdateListener updateListener) {
    this.updateListener = updateListener;
  }
  
  private String itemsKey(DataKind kind) {
    return prefix + ":" + kind.getName();
  }
  
  private String initedKey() {
    return prefix + ":$inited";
  }
  
  private String getRedis(DataKind kind, String key, Jedis jedis) {
    String json = jedis.hget(itemsKey(kind), key);

    if (json == null) {
      logger.debug("[get] Key: {} not found in \"{}\". Returning null", key, kind.getName());
    }
    
    return json;
  }
  
  private static String jsonOrPlaceholder(DataKind kind, SerializedItemDescriptor serializedItem) {
    String s = serializedItem.getSerializedItem();
    if (s != null) {
      return s;
    }
    // For backward compatibility with previous implementations of the Redis integration, we must store a
    // special placeholder string for deleted items. DataKind.serializeItem() will give us this string if
    // we pass a deleted ItemDescriptor.
    return kind.serialize(ItemDescriptor.deletedItem(serializedItem.getVersion()));
  }
  
  static interface UpdateListener {
    void aboutToUpdate(String baseKey, String itemKey);
  }
}
