package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.server.integrations.FileDataReloader.EmptiedFileException;
import com.launchdarkly.sdk.server.integrations.OverrideFileLoader.LoadResult;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FileDataException;

import org.junit.After;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class FileDataReloaderTest {
  private static final Duration SHORT = Duration.ofMillis(50);
  private static final long WAIT_MILLIS = 5000;

  private final LogCapture logCapture = Logs.capture();
  private final LDLogger logger = LDLogger.withAdapter(logCapture, "");
  private final List<FileDataReloader> reloaders = new ArrayList<>();

  @After
  public void closeReloaders() {
    for (FileDataReloader r : reloaders) {
      r.close();
    }
  }

  private static LoadResult resultWithHash(int... values) {
    byte[] hash = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      hash[i] = (byte) values[i];
    }
    return new LoadResult(Collections.emptyList(), Collections.emptyList(), 0, 0, hash);
  }

  private static LoadResult resultWithoutHash() {
    return new LoadResult(Collections.emptyList(), Collections.emptyList(), 0, 0, null);
  }

  private static FileDataException failure(String message) {
    return new FileDataException(message, null, null);
  }

  /**
   * One step of a scripted loader: it returns a result or throws.
   */
  private interface Step {
    LoadResult run() throws FileDataException, EmptiedFileException;
  }

  /**
   * A scripted loader: each call takes the next step from the queue. A step either returns a result
   * or throws. When the queue is empty the last step repeats. It records the argument of each call
   * and every accepted result.
   */
  private static final class ScriptedLoader implements FileDataReloader.Loader {
    final AtomicInteger calls = new AtomicInteger();
    final List<Duration> retryDelayArguments = Collections.synchronizedList(new ArrayList<>());
    final List<LoadResult> accepted = Collections.synchronizedList(new ArrayList<>());
    final LinkedBlockingQueue<Step> steps = new LinkedBlockingQueue<>();
    volatile Step last;
    volatile CountDownLatch entered;
    volatile CountDownLatch release;

    ScriptedLoader then(LoadResult result) {
      steps.add(() -> result);
      return this;
    }

    ScriptedLoader thenFail(String message) {
      steps.add(() -> {
        throw failure(message);
      });
      return this;
    }

    ScriptedLoader thenThrow(RuntimeException e) {
      steps.add(() -> {
        throw e;
      });
      return this;
    }

    ScriptedLoader thenEmptied(Path path) {
      steps.add(() -> {
        throw new EmptiedFileException(Collections.singletonList(path));
      });
      return this;
    }

    @Override
    public LoadResult load(Duration retryDelay) throws FileDataException, EmptiedFileException {
      // Both latches are read before the entered signal, so a test that clears them after the
      // signal cannot change what this call does.
      CountDownLatch enteredLatch = entered;
      CountDownLatch releaseLatch = release;
      retryDelayArguments.add(retryDelay);
      calls.incrementAndGet();
      if (enteredLatch != null) {
        enteredLatch.countDown();
      }
      if (releaseLatch != null) {
        try {
          releaseLatch.await(WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      Step step = steps.poll();
      if (step == null) {
        step = last;
      } else {
        last = step;
      }
      return step.run();
    }

    @Override
    public void accepted(LoadResult result) {
      accepted.add(result);
    }
  }

  private static class RecordingHandler implements FileDataReloader.Handler {
    final List<LoadResult> applied = Collections.synchronizedList(new ArrayList<>());
    final List<FileDataException> errors = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void apply(LoadResult result) {
      applied.add(result);
    }

    @Override
    public void onError(FileDataException e) {
      errors.add(e);
    }
  }

  private FileDataReloader reloader(ScriptedLoader loader, RecordingHandler handler,
      Duration debounce, Duration retry, boolean skipUnchanged) {
    FileDataReloader r = new FileDataReloader(loader, handler, logger, debounce, retry, skipUnchanged);
    reloaders.add(r);
    return r;
  }

  private static void awaitAtLeast(AtomicInteger counter, int expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WAIT_MILLIS;
    while (counter.get() < expected) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for count " + expected + ", got " + counter.get());
      }
      Thread.sleep(5);
    }
  }

  private static void awaitSize(List<?> list, int expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WAIT_MILLIS;
    while (list.size() < expected) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for size " + expected + ", got " + list.size());
      }
      Thread.sleep(5);
    }
  }

  @Test
  public void initialLoadAppliesResultSynchronously() {
    LoadResult result = resultWithHash(1);
    ScriptedLoader loader = new ScriptedLoader().then(result);
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, Duration.ZERO, true);

    r.reloadNow();

    assertEquals(1, loader.calls.get());
    assertEquals(1, handler.applied.size());
    assertEquals(result, handler.applied.get(0));
    assertTrue(handler.errors.isEmpty());
  }

  @Test
  public void failedLoadReportsErrorAndAppliesNothing() {
    ScriptedLoader loader = new ScriptedLoader().thenFail("bad file");
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, Duration.ZERO, true);

    r.reloadNow();

    assertTrue(handler.applied.isEmpty());
    assertEquals(1, handler.errors.size());
    assertThat(FileDataReloader.describe(handler.errors.get(0)), equalTo("bad file"));
    assertThat(logCapture.getMessageStrings(), hasItem("ERROR:Unable to load flags: bad file"));
  }

  @Test
  public void reloaderThatIsNeverTriggeredStartsNoThread() {
    ScriptedLoader loader = new ScriptedLoader().then(resultWithHash(1));
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, SHORT, SHORT, true);

    r.reloadNow();

    assertFalse(r.hasWorker());
  }

  @Test
  public void triggerReloadsAfterDebounceDelay() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().then(resultWithHash(1)).then(resultWithHash(2));
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, SHORT, Duration.ZERO, true);
    r.reloadNow();

    r.trigger();

    awaitSize(handler.applied, 2);
    assertTrue(r.hasWorker());
    assertThat(logCapture.getMessageStrings(), hasItem("INFO:Reloading flag data after detecting a change"));
  }

  @Test
  public void debounceCoalescesBurstOfTriggersIntoOneReload() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().then(resultWithHash(1)).then(resultWithHash(2));
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ofMillis(150), Duration.ZERO, false);
    r.reloadNow();

    for (int i = 0; i < 10; i++) {
      r.trigger();
    }

    awaitSize(handler.applied, 2);
    Thread.sleep(400);
    assertEquals(2, loader.calls.get());
    assertEquals(2, handler.applied.size());
  }

  @Test
  public void debounceWindowIsExtendedByEachTrigger() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().then(resultWithHash(1)).then(resultWithHash(2));
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ofMillis(200), Duration.ZERO, false);
    r.reloadNow();

    // Triggers arrive every 50ms for 400ms. The window is 200ms, so no reload happens while they
    // keep arriving.
    for (int i = 0; i < 8; i++) {
      r.trigger();
      Thread.sleep(50);
    }
    assertEquals(1, loader.calls.get());

    awaitSize(handler.applied, 2);
    Thread.sleep(300);
    assertEquals(2, loader.calls.get());
  }

  @Test
  public void zeroDebounceReloadsOnEveryTrigger() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().then(resultWithHash(1));
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, Duration.ZERO, false);
    r.reloadNow();

    r.trigger();
    awaitAtLeast(loader.calls, 2);
    r.trigger();
    awaitAtLeast(loader.calls, 3);

    awaitSize(handler.applied, 3);
  }

  @Test
  public void failedInitialLoadRetriesWithoutFurtherTriggers() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().thenFail("first").thenFail("first").then(resultWithHash(1));
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, SHORT, true);

    r.reloadNow();
    assertTrue(r.hasWorker());

    awaitSize(handler.applied, 1);
    assertThat(loader.calls.get(), greaterThanOrEqualTo(3));
    assertThat(logCapture.getMessageStrings(), hasItem("DEBUG:Retrying flag data load after earlier failure"));
  }

  @Test
  public void identicalRepeatedFailureIsReportedOnceUntilItChanges() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().thenFail("same").thenFail("same").thenFail("same")
        .thenFail("different").then(resultWithHash(1));
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, SHORT, true);

    r.reloadNow();

    awaitSize(handler.applied, 1);
    assertEquals(2, handler.errors.size());
    assertThat(FileDataReloader.describe(handler.errors.get(0)), equalTo("same"));
    assertThat(FileDataReloader.describe(handler.errors.get(1)), equalTo("different"));
    List<String> messages = logCapture.getMessageStrings();
    assertEquals(1, messages.stream().filter(m -> m.equals("ERROR:Unable to load flags: same")).count());
    assertThat(messages, hasItem("DEBUG:Unable to load flags: same"));
    assertThat(messages, hasItem("ERROR:Unable to load flags: different"));
  }

  @Test
  public void successReArmsFailureReporting() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().thenFail("same").then(resultWithHash(1)).thenFail("same")
        .then(resultWithHash(2));
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, SHORT, true);

    r.reloadNow(); // fails, retry applies result 1
    awaitSize(handler.applied, 1);
    r.trigger(); // fails again with the same message, retry applies result 2
    awaitSize(handler.applied, 2);

    assertEquals(2, handler.errors.size());
  }

  @Test
  public void retryStopsAfterSuccess() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().thenFail("first").then(resultWithHash(1));
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, SHORT, true);

    r.reloadNow();
    awaitSize(handler.applied, 1);
    int callsAfterSuccess = loader.calls.get();

    Thread.sleep(300);
    assertEquals(callsAfterSuccess, loader.calls.get());
  }

  @Test
  public void reloadWithUnchangedContentIsSkipped() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().then(resultWithHash(1)).then(resultWithHash(1)).then(resultWithHash(2));
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, Duration.ZERO, true);

    r.reloadNow();
    r.trigger();
    awaitAtLeast(loader.calls, 2);
    r.trigger();
    awaitAtLeast(loader.calls, 3);

    awaitSize(handler.applied, 2);
    assertEquals(2, handler.applied.size());
  }

  @Test
  public void recoveryAppliesEvenWhenContentIsUnchanged() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().then(resultWithHash(1)).thenFail("broken").then(resultWithHash(1));
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, Duration.ZERO, true);

    r.reloadNow();
    r.trigger();
    awaitSize(handler.errors, 1);
    r.trigger();

    awaitSize(handler.applied, 2);
  }

  @Test
  public void everyReloadIsAppliedWhenSkipUnchangedIsOff() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().then(resultWithHash(1));
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, Duration.ZERO, false);

    r.reloadNow();
    r.trigger();

    awaitSize(handler.applied, 2);
  }

  @Test
  public void nullHashIsNeverTreatedAsUnchanged() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().then(resultWithoutHash());
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, Duration.ZERO, true);

    r.reloadNow();
    r.trigger();

    awaitSize(handler.applied, 2);
  }

  @Test
  public void closeStopsFurtherReloads() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().then(resultWithHash(1));
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, Duration.ZERO, false);
    r.reloadNow();

    r.close();
    r.trigger();
    r.reloadNow();
    Thread.sleep(100);

    assertEquals(1, loader.calls.get());
    assertEquals(1, handler.applied.size());
  }

  @Test
  public void closeCancelsPendingRetry() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().thenFail("broken");
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, Duration.ofMillis(100), true);
    r.reloadNow();

    r.close();
    Thread.sleep(300);

    assertEquals(1, loader.calls.get());
  }

  @Test
  public void closeIsIdempotent() {
    ScriptedLoader loader = new ScriptedLoader().then(resultWithHash(1));
    FileDataReloader r = reloader(loader, new RecordingHandler(), SHORT, SHORT, true);
    r.trigger();
    r.close();
    r.close();
  }

  @Test
  public void closeDoesNotWaitForInFlightReloadAndDropsItsResult() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().then(resultWithHash(1));
    loader.entered = new CountDownLatch(1);
    loader.release = new CountDownLatch(1);
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, Duration.ZERO, false);

    r.trigger();
    assertTrue(loader.entered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));

    long start = System.currentTimeMillis();
    r.close();
    assertThat(System.currentTimeMillis() - start, lessThan(1000L));

    loader.release.countDown();
    Thread.sleep(200);
    assertTrue(handler.applied.isEmpty());
  }

  @Test
  public void reloadsAreSerializedBetweenCallerAndWorker() throws Exception {
    // The loader blocks on the worker thread. A synchronous reload from the caller must wait for
    // it rather than run concurrently.
    ScriptedLoader loader = new ScriptedLoader().then(resultWithHash(1)).then(resultWithHash(2));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    loader.entered = entered;
    loader.release = release;
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, Duration.ZERO, false);

    r.trigger();
    assertTrue(entered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
    // Later calls must not block.
    loader.entered = null;
    loader.release = null;

    AtomicReference<Boolean> syncReloadFinished = new AtomicReference<>(false);
    Thread caller = new Thread(() -> {
      r.reloadNow();
      syncReloadFinished.set(true);
    });
    caller.start();
    Thread.sleep(150);
    assertFalse(syncReloadFinished.get());
    assertEquals(1, loader.calls.get());

    release.countDown();
    caller.join(WAIT_MILLIS);
    assertTrue(syncReloadFinished.get());
    assertEquals(2, loader.calls.get());
    assertEquals(2, handler.applied.size());
  }

  @Test
  public void applyThatThrowsIsReportedAsAFailureAndRetried() throws Exception {
    LoadResult result = resultWithHash(1);
    ScriptedLoader loader = new ScriptedLoader().then(result);
    AtomicInteger applies = new AtomicInteger();
    RecordingHandler handler = new RecordingHandler() {
      @Override
      public void apply(LoadResult r) {
        if (applies.incrementAndGet() == 1) {
          throw new IllegalStateException("consumer failed");
        }
        super.apply(r);
      }
    };
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, SHORT, true);

    // The initial load's apply throws. The reload returns normally and reports the failure.
    r.reloadNow();
    assertTrue(handler.applied.isEmpty());
    assertEquals(1, handler.errors.size());
    assertThat(handler.errors.get(0).getCause(), instanceOf(IllegalStateException.class));
    assertThat(logCapture.getMessageStrings(), hasItem(startsWith("ERROR:Unable to load flags:")));

    // The retry applies the same content without a further trigger.
    awaitSize(handler.applied, 1);
    assertEquals(result, handler.applied.get(0));

    // Only now is the content remembered: a further reload of it is skipped.
    r.trigger();
    awaitAtLeast(loader.calls, 3);
    assertEquals(1, handler.applied.size());
  }

  @Test
  public void unexpectedRuntimeExceptionFromLoaderIsLoggedAndWorkerSurvives() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().thenThrow(new IllegalStateException("loader failed"))
        .then(resultWithHash(1));
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, Duration.ZERO, false);

    // The first reload's loader throws something other than a file data failure.
    r.trigger();
    awaitAtLeast(loader.calls, 1);
    // The worker must still run the next reload, and the exception must have been logged.
    r.trigger();
    awaitSize(handler.applied, 1);
    assertThat(logCapture.getMessageStrings(),
        hasItem(startsWith("ERROR:Unexpected error while reloading flag data:")));
  }

  private static final Path EMPTIED = Paths.get("/overrides/emptied.yaml");
  private static final String READ_AGAIN_MESSAGE = "DEBUG:File " + EMPTIED
      + " is empty, possibly while it is being rewritten; reading it again before applying it";

  @Test
  public void emptiedFileIsReadAgainAfterTheRetryDelayWithoutReportingAFailure() throws Exception {
    LoadResult first = resultWithHash(1);
    LoadResult rewritten = resultWithHash(2);
    ScriptedLoader loader = new ScriptedLoader().then(first).thenEmptied(EMPTIED).then(rewritten);
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, SHORT, true);
    r.reloadNow();

    // The load that finds the file emptied applies nothing. Only the retry, with no further
    // trigger, loads again.
    r.trigger();
    awaitSize(handler.applied, 2);

    assertEquals(first, handler.applied.get(0));
    assertEquals(rewritten, handler.applied.get(1));
    assertEquals(3, loader.calls.get());
    assertTrue(handler.errors.isEmpty());
    List<String> messages = logCapture.getMessageStrings();
    assertThat(messages, hasItem(READ_AGAIN_MESSAGE));
    assertThat(messages, hasItem("DEBUG:Reading flag data again after finding an empty file"));
    assertThat(messages, not(hasItem("DEBUG:Retrying flag data load after earlier failure")));
    for (String message : messages) {
      assertFalse(message, message.startsWith("ERROR:"));
    }
  }

  @Test
  public void loaderIsGivenTheRetryDelayOrZeroWithoutRetries() {
    ScriptedLoader withRetries = new ScriptedLoader().then(resultWithHash(1));
    reloader(withRetries, new RecordingHandler(), Duration.ZERO, SHORT, true).reloadNow();
    assertEquals(Collections.singletonList(SHORT), withRetries.retryDelayArguments);

    // Without retries nothing would read a deferred file again, so the loader must not defer one.
    ScriptedLoader withoutRetries = new ScriptedLoader().then(resultWithHash(1));
    reloader(withoutRetries, new RecordingHandler(), Duration.ZERO, Duration.ofMillis(-1), true).reloadNow();
    assertEquals(Collections.singletonList(Duration.ZERO), withoutRetries.retryDelayArguments);
  }

  @Test
  public void loaderIsToldOfEveryResultThatBecomesTheDataInEffect() throws Exception {
    // An applied result and a result skipped as unchanged are the data in effect. A failure, a
    // deferral and a result that the consumer rejects are not.
    LoadResult first = resultWithHash(1);
    LoadResult unchanged = resultWithHash(1);
    LoadResult rejected = resultWithHash(2);
    LoadResult last = resultWithHash(3);
    ScriptedLoader loader = new ScriptedLoader().then(first).then(unchanged).thenFail("broken")
        .thenEmptied(EMPTIED).then(rejected).then(last);
    RecordingHandler handler = new RecordingHandler() {
      @Override
      public void apply(LoadResult r) {
        if (r == rejected) {
          throw new IllegalStateException("consumer failed");
        }
        super.apply(r);
      }
    };
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, Duration.ZERO, true);
    r.reloadNow();
    for (int i = 2; i <= 6; i++) {
      r.trigger();
      awaitAtLeast(loader.calls, i);
    }
    awaitSize(loader.accepted, 3);

    assertEquals(Arrays.asList(first, unchanged, last), loader.accepted);
    assertEquals(Arrays.asList(first, last), handler.applied);
  }

  @Test
  public void failureAfterAReadAgainIsRetriedAsAFailure() throws Exception {
    ScriptedLoader loader = new ScriptedLoader().then(resultWithHash(1)).thenEmptied(EMPTIED).thenFail("broken")
        .then(resultWithHash(2));
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, SHORT, true);
    r.reloadNow();

    r.trigger();
    awaitSize(handler.applied, 2);
    assertEquals(4, loader.calls.get());
    assertEquals(1, handler.errors.size());
    List<String> messages = logCapture.getMessageStrings();
    assertThat(messages, hasItem("DEBUG:Reading flag data again after finding an empty file"));
    assertThat(messages, hasItem("DEBUG:Retrying flag data load after earlier failure"));
  }

  @Test
  public void readingAnEmptiedFileAgainDoesNotEndARecovery() throws Exception {
    // After a failure, the next success applies even when its content is unchanged. A read again
    // in between is not that success.
    LoadResult result = resultWithHash(1);
    ScriptedLoader loader = new ScriptedLoader().then(result).thenFail("broken").thenEmptied(EMPTIED).then(result);
    RecordingHandler handler = new RecordingHandler();
    FileDataReloader r = reloader(loader, handler, Duration.ZERO, SHORT, true);
    r.reloadNow();

    r.trigger();
    awaitSize(handler.applied, 2);
    assertEquals(4, loader.calls.get());
    assertEquals(1, handler.errors.size());
    assertThat(logCapture.getMessageStrings(), hasItem(READ_AGAIN_MESSAGE));
  }
}
