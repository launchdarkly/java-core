package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.subsystems.BigSegmentStore;
import com.launchdarkly.sdk.server.subsystems.BigSegmentStoreTypes;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;

import redis.clients.jedis.Jedis;

@SuppressWarnings("javadoc")
public class RedisBigSegmentStoreImplTest extends BigSegmentStoreTestBase {

  @Override
  protected ComponentConfigurer<BigSegmentStore> makeStore(String prefix) {
    return Redis.bigSegmentStore().prefix(prefix);
  }

  @Override
  protected void clearData(String prefix) {
    prefix = prefix == null || prefix.isEmpty() ? RedisStoreBuilder.DEFAULT_PREFIX : prefix;
    try (Jedis client = new Jedis("localhost", 6379)) {
      for (String key : client.keys(prefix + ":*")) {
        client.del(key);
      }
    }
  }

  @Override
  protected void setMetadata(String prefix, BigSegmentStoreTypes.StoreMetadata storeMetadata) {
    try (Jedis client = new Jedis("localhost", 6379)) {
      client.set(prefix + ":big_segments_synchronized_on",
          storeMetadata != null ? Long.toString(storeMetadata.getLastUpToDate()) : "");
    }
  }

  @Override
  protected void setSegments(String prefix,
                             String userHashKey,
                             Iterable<String> includedSegmentRefs,
                             Iterable<String> excludedSegmentRefs) {
    try (Jedis client = new Jedis("localhost", 6379)) {
      String includeKey = prefix + ":big_segment_include:" + userHashKey;
      String excludeKey = prefix + ":big_segment_exclude:" + userHashKey;
      for (String includedSegmentRef : includedSegmentRefs) {
        client.sadd(includeKey, includedSegmentRef);
      }
      for (String excludedSegmentRef : excludedSegmentRefs) {
        client.sadd(excludeKey, excludedSegmentRef);
      }
    }
  }
}
