package com.launchdarkly.sdk.server.integrations;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.integrations.FileDataSourceBuilder.SourceInfo;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FileDataException;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FlagFactory;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FlagFileParser;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FlagFileRep;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.ErrorInfo;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.ErrorKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ChangeSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ChangeSetType;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.DataModel.SEGMENTS;

/**
 * Base class containing shared logic for file data source implementations.
 */
class FileDataSourceBase {
    protected final List<SourceInfo> sources;
    protected final FileData.DuplicateKeysHandling duplicateKeysHandling;
    protected final LDLogger logger;
    private final DataLoader dataLoader;

    private final boolean persist;

    protected FileDataSourceBase(
        List<SourceInfo> sources,
        FileData.DuplicateKeysHandling duplicateKeysHandling,
        LDLogger logger,
        boolean persist
    ) {
        this.sources = new ArrayList<>(sources);
        this.duplicateKeysHandling = duplicateKeysHandling;
        this.logger = logger;
        this.dataLoader = new DataLoader(sources);
        this.persist = persist;
    }

    /**
     * Loads data from all configured files and returns an FDv2SourceResult.
     *
     * @return an FDv2SourceResult containing either a ChangeSet or an error status
     */
    protected FDv2SourceResult loadData() {
        DataBuilder builder = new DataBuilder(duplicateKeysHandling);
        try {
            dataLoader.load(builder);
        } catch (FileDataException e) {
            String description = getErrorDescription(e);
            logger.error(description);
            ErrorInfo errorInfo = new ErrorInfo(
                ErrorKind.INVALID_DATA,
                0,
                description,
                Instant.now()
            );
            // For initializers, file errors are terminal. For synchronizers, they are recoverable.
            return FDv2SourceResult.interrupted(errorInfo, false);
        }

        Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data = builder.build();
        ChangeSet<ItemDescriptor> changeSet = buildChangeSet(data);
        return FDv2SourceResult.changeSet(changeSet, false);
    }

    /**
     * Builds a ChangeSet from the data entries.
     */
    private ChangeSet<ItemDescriptor> buildChangeSet(Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data) {
        return new ChangeSet<>(
            ChangeSetType.Full,
            // File data is currently selector-less.
            Selector.EMPTY,
            data,
            null,  // no environment ID for file data
            persist
        );
    }

    /**
     * Returns the list of source infos for file watching.
     */
    Iterable<SourceInfo> getSources() {
        return sources;
    }

    /**
     * Safely gets an error description from a FileDataException, handling the case
     * where the cause may be null.
     */
    private String getErrorDescription(FileDataException e) {
        // FileDataException.getDescription() has a bug where it calls getCause().toString()
        // without null checking. We work around this by building our own description.
        StringBuilder s = new StringBuilder();
        if (e.getMessage() != null) {
            s.append(e.getMessage());
        }
        if (e.getCause() != null) {
            if (s.length() > 0) {
                s.append(" ");
            }
            s.append("[").append(e.getCause().toString()).append("]");
        }
        return s.toString();
    }

    /**
     * Implements the loading of flag data from one or more files. Will throw an exception if any file can't
     * be read or parsed, or if any flag or segment keys are duplicates.
     */
    static final class DataLoader {
        private final List<SourceInfo> sources;
        private final AtomicInteger lastVersion;

        public DataLoader(List<SourceInfo> sources) {
            this.sources = new ArrayList<>(sources);
            this.lastVersion = new AtomicInteger(0);
        }

        /**
         * Loads data from all sources into the builder.
         *
         * @param builder the data builder to populate
         * @return the version number assigned to this load
         * @throws FileDataException if any file cannot be read or parsed
         */
        public int load(DataBuilder builder) throws FileDataException {
            int version = lastVersion.incrementAndGet();
            for (SourceInfo s : sources) {
                try {
                    byte[] data = s.readData();
                    FlagFileParser parser = FlagFileParser.selectForContent(data);
                    FlagFileRep fileContents = parser.parse(new ByteArrayInputStream(data));
                    if (fileContents.flags != null) {
                        for (Map.Entry<String, LDValue> e : fileContents.flags.entrySet()) {
                            builder.add(FEATURES, e.getKey(), FlagFactory.flagFromJson(e.getValue(), version));
                        }
                    }
                    if (fileContents.flagValues != null) {
                        for (Map.Entry<String, LDValue> e : fileContents.flagValues.entrySet()) {
                            builder.add(FEATURES, e.getKey(), FlagFactory.flagWithValue(e.getKey(), e.getValue(), version));
                        }
                    }
                    if (fileContents.segments != null) {
                        for (Map.Entry<String, LDValue> e : fileContents.segments.entrySet()) {
                            builder.add(SEGMENTS, e.getKey(), FlagFactory.segmentFromJson(e.getValue(), version));
                        }
                    }
                } catch (FileDataException e) {
                    throw new FileDataException(e.getMessage(), e.getCause(), s);
                } catch (IOException e) {
                    throw new FileDataException(null, e, s);
                }
            }
            return version;
        }
    }

    /**
     * Internal data structure that organizes flag/segment data into the format that the feature store
     * expects. Will throw an exception if we try to add the same flag or segment key more than once.
     */
    static final class DataBuilder {
        private final Map<DataKind, Map<String, ItemDescriptor>> allData = new HashMap<>();
        private final FileData.DuplicateKeysHandling duplicateKeysHandling;

        public DataBuilder(FileData.DuplicateKeysHandling duplicateKeysHandling) {
            this.duplicateKeysHandling = duplicateKeysHandling;
        }

        public Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> build() {
            ImmutableList.Builder<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> allBuilder = ImmutableList.builder();
            for (Map.Entry<DataKind, Map<String, ItemDescriptor>> e0 : allData.entrySet()) {
                allBuilder.add(new AbstractMap.SimpleEntry<>(e0.getKey(), new KeyedItems<>(e0.getValue().entrySet())));
            }
            return allBuilder.build();
        }

        public void add(DataKind kind, String key, ItemDescriptor item) throws FileDataException {
            Map<String, ItemDescriptor> items = allData.computeIfAbsent(kind, k -> new HashMap<>());
            if (items.containsKey(key)) {
                if (duplicateKeysHandling == FileData.DuplicateKeysHandling.IGNORE) {
                    return;
                }
                throw new FileDataException("in " + kind.getName() + ", key \"" + key + "\" was already defined", null, null);
            }
            items.put(key, item);
        }
    }
}
