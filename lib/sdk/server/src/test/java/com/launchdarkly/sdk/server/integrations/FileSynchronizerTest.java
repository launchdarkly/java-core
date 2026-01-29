package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ChangeSetType;
import com.launchdarkly.testhelpers.TempDir;
import com.launchdarkly.testhelpers.TempFile;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.sdk.server.integrations.FileDataSourceTestData.getResourceContents;
import static com.launchdarkly.sdk.server.integrations.FileDataSourceTestData.resourceFilePath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertNotNull;

@SuppressWarnings("javadoc")
public class FileSynchronizerTest {
    private static final LDLogger testLogger = LDLogger.none();

    @Test
    public void synchronizerReturnsChangeSetOnSuccessfulLoad() throws Exception {
        Synchronizer synchronizer = FileData.synchronizer()
                .filePaths(resourceFilePath("all-properties.json"))
                .build(TestDataSourceBuildInputs.create(testLogger));

        try {
            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertThat(result.getResultType(), equalTo(FDv2SourceResult.ResultType.CHANGE_SET));
            assertThat(result.getChangeSet(), notNullValue());
            assertThat(result.getChangeSet().getType(), equalTo(ChangeSetType.Full));
            assertNotNull(result.getChangeSet().getData());
        } finally {
            synchronizer.close();
        }
    }

    @Test
    public void synchronizerReturnsInterruptedOnMissingFile() throws Exception {
        Synchronizer synchronizer = FileData.synchronizer()
                .filePaths(Paths.get("no-such-file.json"))
                .build(TestDataSourceBuildInputs.create(testLogger));

        try {
            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            // Synchronizers return INTERRUPTED for recoverable errors, not TERMINAL_ERROR
            assertThat(result.getResultType(), equalTo(FDv2SourceResult.ResultType.STATUS));
            assertThat(result.getStatus().getState(), equalTo(FDv2SourceResult.State.INTERRUPTED));
            assertNotNull(result.getStatus().getErrorInfo());
        } finally {
            synchronizer.close();
        }
    }

    @Test
    public void synchronizerReturnsShutdownWhenClosed() throws Exception {
        Synchronizer synchronizer = FileData.synchronizer()
                .filePaths(resourceFilePath("all-properties.json"))
                .build(TestDataSourceBuildInputs.create(testLogger));

        // Get initial result
        CompletableFuture<FDv2SourceResult> initialResult = synchronizer.next();
        FDv2SourceResult result = initialResult.get(5, TimeUnit.SECONDS);
        assertThat(result.getResultType(), equalTo(FDv2SourceResult.ResultType.CHANGE_SET));

        // Start waiting for next result
        CompletableFuture<FDv2SourceResult> nextResult = synchronizer.next();

        // Close the synchronizer
        synchronizer.close();

        // Should return shutdown
        result = nextResult.get(5, TimeUnit.SECONDS);
        assertThat(result.getResultType(), equalTo(FDv2SourceResult.ResultType.STATUS));
        assertThat(result.getStatus().getState(), equalTo(FDv2SourceResult.State.SHUTDOWN));
    }

    @Test
    public void synchronizerCanLoadFromClasspathResource() throws Exception {
        Synchronizer synchronizer = FileData.synchronizer()
                .classpathResources(FileDataSourceTestData.resourceLocation("all-properties.json"))
                .build(TestDataSourceBuildInputs.create(testLogger));

        try {
            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertThat(result.getResultType(), equalTo(FDv2SourceResult.ResultType.CHANGE_SET));
            assertThat(result.getChangeSet(), notNullValue());
        } finally {
            synchronizer.close();
        }
    }

    @Test
    public void synchronizerRespectsIgnoreDuplicateKeysHandling() throws Exception {
        Synchronizer synchronizer = FileData.synchronizer()
                .filePaths(
                        resourceFilePath("flag-only.json"),
                        resourceFilePath("flag-with-duplicate-key.json")
                )
                .duplicateKeysHandling(FileData.DuplicateKeysHandling.IGNORE)
                .build(TestDataSourceBuildInputs.create(testLogger));

        try {
            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            // Should succeed when ignoring duplicates
            assertThat(result.getResultType(), equalTo(FDv2SourceResult.ResultType.CHANGE_SET));
        } finally {
            synchronizer.close();
        }
    }

    @Test
    public void synchronizerFailsOnDuplicateKeysByDefault() throws Exception {
        Synchronizer synchronizer = FileData.synchronizer()
                .filePaths(
                        resourceFilePath("flag-only.json"),
                        resourceFilePath("flag-with-duplicate-key.json")
                )
                .build(TestDataSourceBuildInputs.create(testLogger));

        try {
            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            // Should fail with interrupted error when duplicate keys are not allowed
            assertThat(result.getResultType(), equalTo(FDv2SourceResult.ResultType.STATUS));
            assertThat(result.getStatus().getState(), equalTo(FDv2SourceResult.State.INTERRUPTED));
        } finally {
            synchronizer.close();
        }
    }

    @Test
    public void synchronizerAutoUpdateEmitsNewResultOnFileChange() throws Exception {
        try (TempDir dir = TempDir.create()) {
            try (TempFile file = dir.tempFile(".json")) {
                file.setContents(getResourceContents("flag-only.json"));

                Synchronizer synchronizer = FileData.synchronizer()
                        .filePaths(file.getPath())
                        .autoUpdate(true)
                        .build(TestDataSourceBuildInputs.create(testLogger));

                try {
                    // Get initial result
                    CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
                    FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);
                    assertThat(result.getResultType(), equalTo(FDv2SourceResult.ResultType.CHANGE_SET));

                    // Start waiting for next result
                    CompletableFuture<FDv2SourceResult> nextResultFuture = synchronizer.next();

                    // Modify the file
                    Thread.sleep(200); // Small delay to ensure file watcher is ready
                    file.setContents(getResourceContents("segment-only.json"));

                    // Should get a new result with the updated data
                    // Note: File watching on MacOS can take up to 10 seconds
                    result = nextResultFuture.get(15, TimeUnit.SECONDS);
                    assertThat(result.getResultType(), equalTo(FDv2SourceResult.ResultType.CHANGE_SET));
                } finally {
                    synchronizer.close();
                }
            }
        }
    }
}
