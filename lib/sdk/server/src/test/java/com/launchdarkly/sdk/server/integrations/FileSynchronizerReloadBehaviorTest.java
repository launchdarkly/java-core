package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.ErrorKind;
import com.launchdarkly.testhelpers.TempDir;
import com.launchdarkly.testhelpers.TempFile;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.launchdarkly.sdk.server.integrations.FileDataSourceTestData.getResourceContents;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * Pins the reload behavior of the file data source's synchronizer: it reloads on every file system
 * event, reports every failed load, does not retry on its own, and logs a failure as the plain
 * description at error level. The override source has different rules and its own tests.
 */
@SuppressWarnings("javadoc")
public class FileSynchronizerReloadBehaviorTest {
  private static final long CHANGE_TIMEOUT_SECONDS = 15;
  private static final String MALFORMED = "{\"flags\"";

  private final LogCapture logCapture = Logs.capture();
  private final LDLogger logger = LDLogger.withAdapter(logCapture, "");

  private Synchronizer autoUpdatingSynchronizer(TempFile file) {
    return FileData.synchronizer()
        .filePaths(file.getPath())
        .autoUpdate(true)
        .build(TestDataSourceBuildInputs.create(logger));
  }

  private static FDv2SourceResult await(CompletableFuture<FDv2SourceResult> future) throws Exception {
    return future.get(CHANGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  // Consumes results until none arrives for a short quiet period, then returns the one future that is
  // still outstanding. A single edit can produce more than one file system event, and the synchronizer
  // reloads on each of them. Only one next() future is ever left outstanding, because the queue
  // delivers each result to the oldest waiting future, and a forgotten one would swallow a result.
  private static CompletableFuture<FDv2SourceResult> settle(Synchronizer synchronizer) throws Exception {
    CompletableFuture<FDv2SourceResult> pending = synchronizer.next();
    while (true) {
      try {
        pending.get(500, TimeUnit.MILLISECONDS);
        pending = synchronizer.next();
      } catch (TimeoutException e) {
        return pending;
      }
    }
  }

  private void awaitErrorMessages(int atLeast) throws Exception {
    long deadline = System.currentTimeMillis() + CHANGE_TIMEOUT_SECONDS * 1000;
    while (errorMessages().size() < atLeast) {
      if (System.currentTimeMillis() > deadline) {
        fail("expected at least " + atLeast + " error logs but saw " + errorMessages());
      }
      Thread.sleep(50);
    }
  }

  private List<String> errorMessages() {
    List<String> out = new java.util.ArrayList<>();
    for (LogCapture.Message m : logCapture.getMessages()) {
      if (m.getLevel() == LDLogLevel.ERROR) {
        out.add(m.getText());
      }
    }
    return out;
  }

  @Test
  public void rewriteWithIdenticalContentEmitsAnotherChangeSet() throws Exception {
    try (TempDir dir = TempDir.create()) {
      try (TempFile file = dir.tempFile(".json")) {
        String contents = getResourceContents("flag-only.json");
        file.setContents(contents);
        try (Synchronizer synchronizer = autoUpdatingSynchronizer(file)) {
          assertThat(await(synchronizer.next()).getResultType(), equalTo(SourceResultType.CHANGE_SET));

          CompletableFuture<FDv2SourceResult> next = synchronizer.next();
          Thread.sleep(200); // let the watcher register before the change
          file.setContents(contents); // same bytes, new modification time

          // The content did not change, and the synchronizer still delivers a new change set.
          assertThat(await(next).getResultType(), equalTo(SourceResultType.CHANGE_SET));
        }
      }
    }
  }

  @Test
  public void everyFailedReloadIsReportedAndLoggedAsThePlainDescription() throws Exception {
    try (TempDir dir = TempDir.create()) {
      try (TempFile file = dir.tempFile(".json")) {
        file.setContents(getResourceContents("flag-only.json"));
        try (Synchronizer synchronizer = autoUpdatingSynchronizer(file)) {
          assertThat(await(synchronizer.next()).getResultType(), equalTo(SourceResultType.CHANGE_SET));

          CompletableFuture<FDv2SourceResult> next = synchronizer.next();
          Thread.sleep(200);
          file.setContents(MALFORMED);
          FDv2SourceResult first = await(next);
          assertThat(first.getResultType(), equalTo(SourceResultType.STATUS));
          assertThat(first.getStatus().getState(), equalTo(SourceSignal.INTERRUPTED));
          assertThat(first.getStatus().getErrorInfo().getKind(), equalTo(ErrorKind.INVALID_DATA));
          CompletableFuture<FDv2SourceResult> pending = settle(synchronizer);
          int errorsAfterFirstFailure = errorMessages().size();

          // The same malformed content again is the same failure. It is reported again, not deduplicated.
          file.setContents(MALFORMED);
          FDv2SourceResult second = await(pending);
          assertThat(second.getResultType(), equalTo(SourceResultType.STATUS));
          assertThat(second.getStatus().getState(), equalTo(SourceSignal.INTERRUPTED));
          assertEquals(first.getStatus().getErrorInfo().getMessage(), second.getStatus().getErrorInfo().getMessage());
          awaitErrorMessages(errorsAfterFirstFailure + 1);

          // Each failure is logged at error level as the plain description: the parser message and
          // the cause in brackets. There is no prefix, and the description does not name the file.
          List<String> errors = errorMessages();
          assertThat(errors.size(), greaterThanOrEqualTo(2));
          for (String message : errors) {
            assertThat(message, startsWith("cannot parse JSON [com.google.gson.JsonSyntaxException"));
            assertThat(message, endsWith("]"));
            assertThat(message, not(containsString(file.getPath().toString())));
          }
          assertEquals(errors.get(0), first.getStatus().getErrorInfo().getMessage());
        }
      }
    }
  }

  @Test
  public void failedReloadIsNotRetriedWithoutAFileChange() throws Exception {
    try (TempDir dir = TempDir.create()) {
      try (TempFile file = dir.tempFile(".json")) {
        file.setContents(getResourceContents("flag-only.json"));
        try (Synchronizer synchronizer = autoUpdatingSynchronizer(file)) {
          assertThat(await(synchronizer.next()).getResultType(), equalTo(SourceResultType.CHANGE_SET));

          CompletableFuture<FDv2SourceResult> next = synchronizer.next();
          Thread.sleep(200);
          file.setContents(MALFORMED);
          assertThat(await(next).getStatus().getState(), equalTo(SourceSignal.INTERRUPTED));
          CompletableFuture<FDv2SourceResult> later = settle(synchronizer);
          int errorsAfterFailure = errorMessages().size();

          // With the file untouched, nothing reloads: no result, no further error log, no retry log.
          try {
            FDv2SourceResult unexpected = later.get(2, TimeUnit.SECONDS);
            fail("unexpected reload result: " + unexpected.getResultType());
          } catch (TimeoutException e) {
            // expected
          }
          assertEquals(errorsAfterFailure, errorMessages().size());
          for (LogCapture.Message m : logCapture.getMessages()) {
            assertFalse(m.getText(), m.getText().contains("Retrying"));
          }
          assertFalse(later.isDone());
        }
      }
    }
  }

  @Test
  public void missingFileAtStartupIsAnErrorLoggedWithThePath() throws Exception {
    try (TempDir dir = TempDir.create()) {
      java.nio.file.Path missing = dir.getPath().resolve("missing.json");
      try (Synchronizer synchronizer = FileData.synchronizer().filePaths(missing)
          .build(TestDataSourceBuildInputs.create(logger))) {
        FDv2SourceResult result = await(synchronizer.next());
        assertThat(result.getResultType(), equalTo(SourceResultType.STATUS));
        assertThat(result.getStatus().getState(), equalTo(SourceSignal.INTERRUPTED));
        // The description is the cause in brackets. The path appears only inside the cause text.
        List<String> errors = errorMessages();
        assertEquals(1, errors.size());
        assertEquals("[java.nio.file.NoSuchFileException: " + missing + "]", errors.get(0));
        assertEquals(errors.get(0), result.getStatus().getErrorInfo().getMessage());
      }
    }
  }
}
