package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.subsystems.BigSegmentStore;
import com.launchdarkly.sdk.server.subsystems.BigSegmentStoreTypes;

import java.util.Set;

import redis.clients.jedis.Jedis;

final class RedisBigSegmentStoreImpl extends RedisStoreImplBase implements BigSegmentStore {
  private final String syncTimeKey;
  private final String includedKeyPrefix;
  private final String excludedKeyPrefix;

  RedisBigSegmentStoreImpl(RedisStoreBuilder<BigSegmentStore> builder, LDLogger baseLogger) {
    super(builder, baseLogger.subLogger("BigSegments").subLogger("Redis"));
    syncTimeKey = prefix + ":big_segments_synchronized_on";
    includedKeyPrefix = prefix + ":big_segment_include:";
    excludedKeyPrefix = prefix + ":big_segment_exclude:";
  }

  @Override
  public BigSegmentStoreTypes.Membership getMembership(String userHash) {
    try (Jedis jedis = pool.getResource()) {
      Set<String> includedRefs = jedis.smembers(includedKeyPrefix + userHash);
      Set<String> excludedRefs = jedis.smembers(excludedKeyPrefix + userHash);
      return BigSegmentStoreTypes.createMembershipFromSegmentRefs(includedRefs, excludedRefs);
    }
  }

  @Override
  public BigSegmentStoreTypes.StoreMetadata getMetadata() {
    try (Jedis jedis = pool.getResource()) {
      String value = jedis.get(syncTimeKey);
      if (value == null || value.isEmpty()) {
        return null;
      }
      return new BigSegmentStoreTypes.StoreMetadata(Long.parseLong(value));
    }
  }
}
