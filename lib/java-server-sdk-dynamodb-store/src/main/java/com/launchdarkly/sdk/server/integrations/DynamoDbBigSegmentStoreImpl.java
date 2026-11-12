package com.launchdarkly.sdk.server.integrations;

import static com.launchdarkly.sdk.server.subsystems.BigSegmentStoreTypes.createMembershipFromSegmentRefs;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.subsystems.BigSegmentStore;
import com.launchdarkly.sdk.server.subsystems.BigSegmentStoreTypes;

import java.util.List;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

final class DynamoDbBigSegmentStoreImpl extends DynamoDbStoreImplBase implements BigSegmentStore {
  private final static String MEMBERSHIP_KEY = "big_segments_user";
  private final static String INCLUDED_ATTR = "included";
  private final static String EXCLUDED_ATTR = "excluded";

  private final static String METADATA_KEY = "big_segments_metadata";
  private final static String SYNC_TIME_ATTR = "synchronizedOn";

  DynamoDbBigSegmentStoreImpl(
    DynamoDbClient client,
    boolean wasExistingClient,
    String tableName,
    String prefix,
    LDLogger baseLogger
    ) {
    super(client, wasExistingClient, tableName, prefix,
      baseLogger.subLogger("BigSegments").subLogger("DynamoDb"));
  }

  @Override
  public BigSegmentStoreTypes.Membership getMembership(String userHash) {
    String namespaceKey = prefixedNamespace(MEMBERSHIP_KEY);
    GetItemResponse response = getItemByKeys(namespaceKey, userHash);
    if (response == null || response.item() == null || response.item().isEmpty()) {
      return null;
    }
    List<String> includedRefs = stringListFromAttrValue(response.item().get(INCLUDED_ATTR));
    List<String> excludedRefs = stringListFromAttrValue(response.item().get(EXCLUDED_ATTR));
    return createMembershipFromSegmentRefs(includedRefs, excludedRefs);
  }

  private static List<String> stringListFromAttrValue(AttributeValue attrValue) {
    return attrValue == null ? null : attrValue.ss();
  }

  @Override
  public BigSegmentStoreTypes.StoreMetadata getMetadata() {
    String key = prefixedNamespace(METADATA_KEY);
    GetItemResponse response = getItemByKeys(key, key);
    if (response == null || response.item() == null) {
      return null;
    }
    AttributeValue syncTimeValue = response.item().get(SYNC_TIME_ATTR);
    if (syncTimeValue == null) {
      return null;
    }
    String syncTimeString = syncTimeValue.n();
    if (syncTimeString == null) {
      return null;
    }
    return new BigSegmentStoreTypes.StoreMetadata(Long.parseLong(syncTimeString));
  }
}
