package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.server.FDv2DataSourceConditions.Condition;
import com.launchdarkly.sdk.server.FDv2DataSourceConditions.FallbackCondition;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import org.junit.After;
import java.util.Map;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class FDv2DataSourceFallbackConditionTest extends BaseTest {

    private ScheduledExecutorService executor;

    @After
    public void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    private ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> makeChangeSet() {
        return new ChangeSet<>(
            ChangeSetType.None,
            Selector.EMPTY,
            null,
            null,
            true
        );
    }

    @Test
    public void executeReturnsCompletableFuture() {
        executor = Executors.newScheduledThreadPool(1);
        FallbackCondition condition = new FallbackCondition(executor, Duration.ofSeconds(120));

        CompletableFuture<Condition> result = condition.execute();

        assertFalse(result.isDone());
    }

    @Test
    public void getTypeReturnsFallback() {
        executor = Executors.newScheduledThreadPool(1);
        FallbackCondition condition = new FallbackCondition(executor, Duration.ofSeconds(120));

        assertEquals(Condition.ConditionType.FALLBACK, condition.getType());
    }

    @Test
    public void interruptedStatusStartsTimerThatCompletesResultFuture() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        FallbackCondition condition = new FallbackCondition(executor, Duration.ofSeconds(1));

        CompletableFuture<Condition> resultFuture = condition.execute();
        assertFalse(resultFuture.isDone());

        // Inform with INTERRUPTED status
        boolean replace = condition.inform(
            FDv2SourceResult.interrupted(
                new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, 500, null, Instant.now()),
                false
            )
        );

        // The condition does not ask to be replaced, and the future should still not be done immediately
        assertFalse(replace);
        assertFalse(resultFuture.isDone());

        // Wait for timeout to fire
        Condition result = resultFuture.get(2, TimeUnit.SECONDS);

        // Now it should be done and return the condition instance
        assertTrue(resultFuture.isDone());
        assertSame(condition, result);
    }

    @Test
    public void changeSetWithActiveTimerRequestsReplacement() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        FallbackCondition condition = new FallbackCondition(executor, Duration.ofSeconds(1));

        // Start timer with INTERRUPTED
        condition.inform(
            FDv2SourceResult.interrupted(
                new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, 500, null, Instant.now()),
                false
            )
        );

        // Inform with CHANGE_SET while the timer is running
        ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = makeChangeSet();
        boolean replace = condition.inform(FDv2SourceResult.changeSet(changeSet, false));

        // The condition asks to be replaced
        assertTrue(replace);
    }

    @Test
    public void changeSetWithoutActiveTimerDoesNotRequestReplacement() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        FallbackCondition condition = new FallbackCondition(executor, Duration.ofSeconds(1));

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Inform with CHANGE_SET without starting a timer first
        ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = makeChangeSet();
        boolean replace = condition.inform(FDv2SourceResult.changeSet(changeSet, false));

        // The condition does not ask to be replaced, and is not met
        assertFalse(replace);
        assertFalse(resultFuture.isDone());
    }

    @Test
    public void multipleInterruptedStatusesDoNotStartMultipleTimers() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        FallbackCondition condition = new FallbackCondition(executor, Duration.ofSeconds(2));

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Inform with INTERRUPTED multiple times
        condition.inform(
            FDv2SourceResult.interrupted(
                new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, 500, null, Instant.now()),
                false
            )
        );

        Thread.sleep(100);

        condition.inform(
            FDv2SourceResult.interrupted(
                new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, 500, null, Instant.now()),
                false
            )
        );

        Thread.sleep(100);

        condition.inform(
            FDv2SourceResult.interrupted(
                new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, 500, null, Instant.now()),
                false
            )
        );

        // Wait for the timer (should only fire once)
        Condition result = resultFuture.get(3, TimeUnit.SECONDS);

        assertTrue(resultFuture.isDone());
        assertSame(condition, result);
    }

    @Test
    public void terminalErrorStatusDoesNotStartTimer() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        FallbackCondition condition = new FallbackCondition(executor, Duration.ofSeconds(1));

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Inform with TERMINAL_ERROR status
        condition.inform(
            FDv2SourceResult.terminalError(
                new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.ERROR_RESPONSE, 401, null, Instant.now()),
                false
            )
        );

        // Wait longer than timeout
        Thread.sleep(1500);

        // Future should still not be complete (no timer started)
        assertFalse(resultFuture.isDone());
    }

    @Test
    public void shutdownStatusDoesNotStartTimer() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        FallbackCondition condition = new FallbackCondition(executor, Duration.ofSeconds(1));

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Inform with SHUTDOWN status
        condition.inform(FDv2SourceResult.shutdown());

        // Wait longer than timeout
        Thread.sleep(1500);

        // Future should still not be complete (no timer started)
        assertFalse(resultFuture.isDone());
    }

    @Test
    public void goodbyeStatusDoesNotStartTimer() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        FallbackCondition condition = new FallbackCondition(executor, Duration.ofSeconds(1));

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Inform with GOODBYE status
        condition.inform(FDv2SourceResult.goodbye("server-requested", false));

        // Wait longer than timeout
        Thread.sleep(1500);

        // Future should still not be complete (no timer started)
        assertFalse(resultFuture.isDone());
    }

    @Test
    public void closeCancelsActiveTimer() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        FallbackCondition condition = new FallbackCondition(executor, Duration.ofSeconds(1));

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Start timer with INTERRUPTED
        condition.inform(
            FDv2SourceResult.interrupted(
                new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, 500, null, Instant.now()),
                false
            )
        );

        // Close the condition
        condition.close();

        // Wait longer than the timeout period
        Thread.sleep(1500);

        // Future should still not be complete (timer was cancelled)
        assertFalse(resultFuture.isDone());
    }

    @Test
    public void closeWithoutActiveTimerDoesNotFail() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        FallbackCondition condition = new FallbackCondition(executor, Duration.ofSeconds(120));

        // Close without starting a timer
        condition.close();

        // Should not throw exception
    }

    @Test
    public void changeSetAfterTimerFiresRequestsReplacement() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        FallbackCondition condition = new FallbackCondition(executor, Duration.ofSeconds(1));

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Start timer
        condition.inform(
            FDv2SourceResult.interrupted(
                new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, 500, null, Instant.now()),
                false
            )
        );

        // Wait for timer to fire
        Condition result = resultFuture.get(2, TimeUnit.SECONDS);
        assertTrue(resultFuture.isDone());
        assertSame(condition, result);

        // Inform with CHANGE_SET after timer has fired
        ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = makeChangeSet();
        boolean replace = condition.inform(FDv2SourceResult.changeSet(changeSet, false));

        // The condition asks to be replaced, and its future remains complete
        assertTrue(replace);
        assertTrue(resultFuture.isDone());
    }

    @Test
    public void factoryCreatesFallbackCondition() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        FallbackCondition.Factory factory = new FallbackCondition.Factory(executor, Duration.ofSeconds(1));

        FallbackCondition condition = (FallbackCondition) factory.build();

        // Verify it works by using it
        CompletableFuture<Condition> resultFuture = condition.execute();
        assertFalse(resultFuture.isDone());

        condition.inform(
            FDv2SourceResult.interrupted(
                new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, 500, null, Instant.now()),
                false
            )
        );

        Condition result = resultFuture.get(2, TimeUnit.SECONDS);
        assertTrue(resultFuture.isDone());
        assertSame(condition, result);
    }

    @Test
    public void executeReturnsTheSameFutureOnMultipleCalls() {
        executor = Executors.newScheduledThreadPool(1);
        FallbackCondition condition = new FallbackCondition(executor, Duration.ofSeconds(120));

        CompletableFuture<Condition> first = condition.execute();
        CompletableFuture<Condition> second = condition.execute();

        assertSame(first, second);
    }

    @Test
    public void closeCanBeCalledMultipleTimes() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        FallbackCondition condition = new FallbackCondition(executor, Duration.ofSeconds(1));

        // Start timer
        condition.inform(
            FDv2SourceResult.interrupted(
                new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, 500, null, Instant.now()),
                false
            )
        );

        // Close multiple times
        condition.close();
        condition.close();
        condition.close();

        // Should not throw exception
    }
}