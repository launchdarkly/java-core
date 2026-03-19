package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.fdv2.ChangeSetType;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.sdk.server.integrations.FileDataSourceTestData.resourceFilePath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertNotNull;

@SuppressWarnings("javadoc")
public class FileInitializerTest {
    private static final LDLogger testLogger = LDLogger.none();

    @Test
    public void initializerReturnsChangeSetOnSuccessfulLoad() throws Exception {

        try (Initializer initializer = FileData.initializer()
            .filePaths(resourceFilePath("all-properties.json"))
            .build(TestDataSourceBuildInputs.create(testLogger))) {
            CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertThat(result.getResultType(), equalTo(SourceResultType.CHANGE_SET));
            assertThat(result.getChangeSet(), notNullValue());
            assertThat(result.getChangeSet().getType(), equalTo(ChangeSetType.Full));
            assertNotNull(result.getChangeSet().getData());
        }
    }

    @Test
    public void initializerReturnsTerminalErrorOnMissingFile() throws Exception {

        try (Initializer initializer = FileData.initializer()
            .filePaths(Paths.get("no-such-file.json"))
            .build(TestDataSourceBuildInputs.create(testLogger))) {
            CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertThat(result.getResultType(), equalTo(SourceResultType.STATUS));
            assertThat(result.getStatus().getState(), equalTo(SourceSignal.TERMINAL_ERROR));
            assertNotNull(result.getStatus().getErrorInfo());
        }
    }

    @Test
    public void initializerReturnsShutdownWhenClosedBeforeRun() throws Exception {
        Initializer initializer = FileData.initializer()
                .filePaths(resourceFilePath("all-properties.json"))
                .build(TestDataSourceBuildInputs.create(testLogger));

        // Close before calling run
        initializer.close();

        CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        assertThat(result.getResultType(), equalTo(SourceResultType.STATUS));
        assertThat(result.getStatus().getState(), equalTo(SourceSignal.SHUTDOWN));
    }

    @Test
    public void initializerCanLoadFromClasspathResource() throws Exception {

        try (Initializer initializer = FileData.initializer()
            .classpathResources(FileDataSourceTestData.resourceLocation("all-properties.json"))
            .build(TestDataSourceBuildInputs.create(testLogger))) {
            CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertThat(result.getResultType(), equalTo(SourceResultType.CHANGE_SET));
            assertThat(result.getChangeSet(), notNullValue());
        }
    }

    @Test
    public void initializerRespectsIgnoreDuplicateKeysHandling() throws Exception {

        try (Initializer initializer = FileData.initializer()
            .filePaths(
                resourceFilePath("flag-only.json"),
                resourceFilePath("flag-with-duplicate-key.json")
            )
            .duplicateKeysHandling(FileData.DuplicateKeysHandling.IGNORE)
            .build(TestDataSourceBuildInputs.create(testLogger))) {
            CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            // Should succeed when ignoring duplicates
            assertThat(result.getResultType(), equalTo(SourceResultType.CHANGE_SET));
        }
    }

    @Test
    public void initializerDefaultsToNotPersisting() throws Exception {

        try (Initializer initializer = FileData.initializer()
            .filePaths(resourceFilePath("all-properties.json"))
            .build(TestDataSourceBuildInputs.create(testLogger))) {
            CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertThat(result.getResultType(), equalTo(SourceResultType.CHANGE_SET));
            assertThat(result.getChangeSet().shouldPersist(), equalTo(false));
        }
    }

    @Test
    public void initializerCanBeConfiguredToPersist() throws Exception {

        try (Initializer initializer = FileData.initializer()
            .filePaths(resourceFilePath("all-properties.json"))
            .shouldPersist(true)
            .build(TestDataSourceBuildInputs.create(testLogger))) {
            CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertThat(result.getResultType(), equalTo(SourceResultType.CHANGE_SET));
            assertThat(result.getChangeSet().shouldPersist(), equalTo(true));
        }
    }

    @Test
    public void initializerCanBeConfiguredToNotPersist() throws Exception {

        try (Initializer initializer = FileData.initializer()
            .filePaths(resourceFilePath("all-properties.json"))
            .shouldPersist(false)
            .build(TestDataSourceBuildInputs.create(testLogger))) {
            CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertThat(result.getResultType(), equalTo(SourceResultType.CHANGE_SET));
            assertThat(result.getChangeSet().shouldPersist(), equalTo(false));
        }
    }

    @Test
    public void initializerFailsOnDuplicateKeysByDefault() throws Exception {

        try (Initializer initializer = FileData.initializer()
            .filePaths(
                resourceFilePath("flag-only.json"),
                resourceFilePath("flag-with-duplicate-key.json")
            )
            .build(TestDataSourceBuildInputs.create(testLogger))) {
            CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            // Should fail with terminal error when duplicate keys are not allowed
            assertThat(result.getResultType(), equalTo(SourceResultType.STATUS));
            assertThat(result.getStatus().getState(), equalTo(SourceSignal.TERMINAL_ERROR));
        }
    }
}
