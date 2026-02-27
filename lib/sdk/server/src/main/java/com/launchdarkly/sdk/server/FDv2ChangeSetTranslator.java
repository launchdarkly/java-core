package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet.FDv2Change;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet.FDv2ChangeType;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates FDv2 changesets into data store formats.
 */
final class FDv2ChangeSetTranslator {
    private FDv2ChangeSetTranslator() {
    }

    /**
     * Converts an FDv2ChangeSet to a ChangeSet.
     *
     * @param changeset     the FDv2 changeset to convert
     * @param logger        logger for diagnostic messages
     * @param environmentId the environment ID to include in the changeset (may be null)
     * @param shouldPersist true if the data should be persisted to persistent stores, false otherwise
     * @return a ChangeSet containing the converted data
     * @throws IllegalArgumentException if the changeset type is unknown
     */
    public static ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> toChangeSet(
            FDv2ChangeSet changeset,
            LDLogger logger,
            String environmentId,
            boolean shouldPersist) {
        ChangeSetType changeSetType;
        switch (changeset.getType()) {
            case FULL:
                changeSetType = ChangeSetType.Full;
                break;
            case PARTIAL:
                changeSetType = ChangeSetType.Partial;
                break;
            case NONE:
                changeSetType = ChangeSetType.None;
                break;
            default:
                throw new IllegalArgumentException(
                        "Unknown FDv2ChangeSetType: " + changeset.getType() + ". This is an implementation error.");
        }

        // Use a LinkedHashMap to group items by DataKind in a single pass while preserving order
        Map<DataKind, List<Map.Entry<String, ItemDescriptor>>> kindToItems = new LinkedHashMap<>();

        for (FDv2Change change : changeset.getChanges()) {
            DataKind dataKind = getDataKind(change.getKind());

            if (dataKind == null) {
                logger.warn("Unknown data kind '{}' in changeset, skipping", change.getKind());
                continue;
            }

            ItemDescriptor item;

            if (change.getType() == FDv2ChangeType.PUT) {
                if (change.getObject() == null) {
                    logger.warn(
                            "Put operation for {}/{} missing object data, skipping",
                            change.getKind(),
                            change.getKey());
                    continue;
                }
                item = dataKind.deserialize(change.getObject().toString());
            } else if (change.getType() == FDv2ChangeType.DELETE) {
                item = ItemDescriptor.deletedItem(change.getVersion());
            } else {
                throw new IllegalArgumentException(
                        "Unknown FDv2ChangeType: " + change.getType() + ". This is an implementation error.");
            }

            List<Map.Entry<String, ItemDescriptor>> itemsList =
                    kindToItems.computeIfAbsent(dataKind, k -> new ArrayList<>());

            itemsList.add(new AbstractMap.SimpleImmutableEntry<>(change.getKey(), item));
        }

        ImmutableList.Builder<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> dataBuilder =
                ImmutableList.builder();

        for (Map.Entry<DataKind, List<Map.Entry<String, ItemDescriptor>>> entry : kindToItems.entrySet()) {
            dataBuilder.add(
                    new AbstractMap.SimpleImmutableEntry<>(
                            entry.getKey(),
                            new KeyedItems<>(entry.getValue())
                    ));
        }

        return new ChangeSet<>(
                changeSetType,
                changeset.getSelector(),
                dataBuilder.build(),
                environmentId,
                shouldPersist);
    }

    /**
     * Maps an FDv2 object kind to the corresponding DataKind.
     *
     * @param kind the kind string from the FDv2 change
     * @return the corresponding DataKind, or null if the kind is not recognized
     */
    private static DataKind getDataKind(String kind) {
        switch (kind) {
            case "flag":
                return DataModel.FEATURES;
            case "segment":
                return DataModel.SEGMENTS;
            default:
                return null;
        }
    }
}
