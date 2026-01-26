package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.server.FDv2DataSourceConditions.Condition;
import com.launchdarkly.sdk.server.FDv2DataSourceConditions.RecoveryCondition;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes;

import org.junit.After;
import org.junit.Test;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class FDv2DataSourceRecoveryConditionTest extends BaseTest {

    private ScheduledExecutorService executor;

    @After
    public void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    private DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> makeChangeSet() {
        return new DataStoreTypes.ChangeSet<>(
            DataStoreTypes.ChangeSetType.None,
            Selector.EMPTY,
            null,
            null
        );
    }

    @Test
    public void getTypeReturnsRecovery() {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition condition = new RecoveryCondition(executor, 120);

        assertEquals(Condition.ConditionType.RECOVERY, condition.getType());
    }

    @Test
    public void timerStartsImmediatelyAndCompletesResultFuture() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition condition = new RecoveryCondition(executor, 1);

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Future should not be done immediately
        assertFalse(resultFuture.isDone());

        // Wait for timeout to fire
        Condition result = resultFuture.get(2, TimeUnit.SECONDS);

        // Now it should be done and return the condition instance
        assertTrue(resultFuture.isDone());
        assertSame(condition, result);
    }

    @Test
    public void closeCancelsActiveTimer() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition condition = new RecoveryCondition(executor, 1);

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Close the condition before timeout
        condition.close();

        // Wait longer than the timeout period
        Thread.sleep(1500);

        // Future should still not be complete (timer was cancelled)
        assertFalse(resultFuture.isDone());
    }

    @Test
    public void closeAfterTimerFiresDoesNotCauseError() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition condition = new RecoveryCondition(executor, 1);

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Wait for timer to fire
        Condition result = resultFuture.get(2, TimeUnit.SECONDS);
        assertTrue(resultFuture.isDone());
        assertSame(condition, result);

        // Close after timer has fired
        condition.close();

        // Should not throw exception
    }

    @Test
    public void closeCanBeCalledMultipleTimes() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition condition = new RecoveryCondition(executor, 1);

        // Close multiple times before timer fires
        condition.close();
        condition.close();
        condition.close();

        // Should not throw exception
    }

    @Test
    public void informWithChangeSetDoesNothing() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition condition = new RecoveryCondition(executor, 1);

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Inform with CHANGE_SET
        DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> changeSet = makeChangeSet();
        condition.inform(FDv2SourceResult.changeSet(changeSet, false));

        // Timer should still fire after timeout (inform does nothing)
        Condition result = resultFuture.get(2, TimeUnit.SECONDS);
        assertTrue(resultFuture.isDone());
        assertSame(condition, result);
    }

    @Test
    public void informWithInterruptedStatusDoesNothing() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition condition = new RecoveryCondition(executor, 1);

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Inform with INTERRUPTED status
        condition.inform(
            FDv2SourceResult.interrupted(
                new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, 500, null, Instant.now()),
                false
            )
        );

        // Timer should still fire after timeout (inform does nothing)
        Condition result = resultFuture.get(2, TimeUnit.SECONDS);
        assertTrue(resultFuture.isDone());
        assertSame(condition, result);
    }

    @Test
    public void informWithTerminalErrorStatusDoesNothing() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition condition = new RecoveryCondition(executor, 1);

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Inform with TERMINAL_ERROR status
        condition.inform(
            FDv2SourceResult.terminalError(
                new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.ERROR_RESPONSE, 401, null, Instant.now()),
                false
            )
        );

        // Timer should still fire after timeout (inform does nothing)
        Condition result = resultFuture.get(2, TimeUnit.SECONDS);
        assertTrue(resultFuture.isDone());
        assertSame(condition, result);
    }

    @Test
    public void informWithShutdownStatusDoesNothing() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition condition = new RecoveryCondition(executor, 1);

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Inform with SHUTDOWN status
        condition.inform(FDv2SourceResult.shutdown());

        // Timer should still fire after timeout (inform does nothing)
        Condition result = resultFuture.get(2, TimeUnit.SECONDS);
        assertTrue(resultFuture.isDone());
        assertSame(condition, result);
    }

    @Test
    public void informWithGoodbyeStatusDoesNothing() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition condition = new RecoveryCondition(executor, 1);

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Inform with GOODBYE status
        condition.inform(FDv2SourceResult.goodbye("server-requested", false));

        // Timer should still fire after timeout (inform does nothing)
        Condition result = resultFuture.get(2, TimeUnit.SECONDS);
        assertTrue(resultFuture.isDone());
        assertSame(condition, result);
    }

    @Test
    public void informWithNullDoesNothing() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition condition = new RecoveryCondition(executor, 1);

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Inform with null
        condition.inform(null);

        // Timer should still fire after timeout (inform does nothing)
        Condition result = resultFuture.get(2, TimeUnit.SECONDS);
        assertTrue(resultFuture.isDone());
        assertSame(condition, result);
    }

    @Test
    public void multipleInformCallsDoNotAffectTimer() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition condition = new RecoveryCondition(executor, 1);

        CompletableFuture<Condition> resultFuture = condition.execute();

        // Multiple inform calls
        DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> changeSet = makeChangeSet();
        condition.inform(FDv2SourceResult.changeSet(changeSet, false));
        condition.inform(
            FDv2SourceResult.interrupted(
                new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, 500, null, Instant.now()),
                false
            )
        );
        condition.inform(FDv2SourceResult.shutdown());

        // Timer should still fire after timeout (all inform calls do nothing)
        Condition result = resultFuture.get(2, TimeUnit.SECONDS);
        assertTrue(resultFuture.isDone());
        assertSame(condition, result);
    }

    @Test
    public void factoryCreatesRecoveryCondition() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition.Factory factory = new RecoveryCondition.Factory(executor, 1);

        RecoveryCondition condition = (RecoveryCondition) factory.build();

        // Verify it works by using it
        CompletableFuture<Condition> resultFuture = condition.execute();
        assertFalse(resultFuture.isDone());

        Condition result = resultFuture.get(2, TimeUnit.SECONDS);
        assertTrue(resultFuture.isDone());
        assertSame(condition, result);
    }

    @Test
    public void factoryGetTypeReturnsRecovery() {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition.Factory factory = new RecoveryCondition.Factory(executor, 1);

        assertEquals(Condition.ConditionType.RECOVERY, factory.getType());
    }

    @Test
    public void executeReturnsTheSameFutureOnMultipleCalls() {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition condition = new RecoveryCondition(executor, 120);

        CompletableFuture<Condition> first = condition.execute();
        CompletableFuture<Condition> second = condition.execute();

        assertSame(first, second);
    }

    @Test
    public void timerStartsImmediatelyOnConstruction() throws Exception {
        executor = Executors.newScheduledThreadPool(1);

        // Create condition with very short timeout
        RecoveryCondition condition = new RecoveryCondition(executor, 1);

        // Get the future
        CompletableFuture<Condition> resultFuture = condition.execute();

        // Verify it's not done yet
        assertFalse(resultFuture.isDone());

        // Wait for it to complete
        Condition result = resultFuture.get(2, TimeUnit.SECONDS);

        // Verify it completed with the condition
        assertSame(condition, result);
    }

    @Test
    public void closeBeforeExecuteDoesNotPreventFutureAccess() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        RecoveryCondition condition = new RecoveryCondition(executor, 1);

        // Close immediately
        condition.close();

        // Should still be able to get the future
        CompletableFuture<Condition> resultFuture = condition.execute();

        // Wait to ensure timer doesn't fire
        Thread.sleep(1500);

        // Future should not be complete (timer was cancelled)
        assertFalse(resultFuture.isDone());
    }
}
