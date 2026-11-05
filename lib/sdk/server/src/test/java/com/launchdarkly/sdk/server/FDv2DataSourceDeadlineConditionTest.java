package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.server.FDv2DataSourceConditions.Condition;
import com.launchdarkly.sdk.server.FDv2DataSourceConditions.Condition.ConditionType;
import com.launchdarkly.sdk.server.FDv2DataSourceConditions.DeadlineCondition;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;

import org.junit.After;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

@SuppressWarnings("javadoc")
public class FDv2DataSourceDeadlineConditionTest extends BaseTest {

    private ScheduledExecutorService executor;

    @After
    public void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    @Test
    public void getTypeReturnsUnpark() {
        executor = Executors.newScheduledThreadPool(1);
        DeadlineCondition condition = new DeadlineCondition(executor, ConditionType.UNPARK, Duration.ofSeconds(120));

        assertEquals(ConditionType.UNPARK, condition.getType());
    }

    @Test
    public void conditionIsMetAfterItsDelay() throws Exception {
        executor = Executors.newScheduledThreadPool(1);

        // Build a condition with a short delay.
        DeadlineCondition condition = new DeadlineCondition(executor, ConditionType.UNPARK, Duration.ofMillis(50));

        // The condition is met with itself after the delay.
        assertSame(condition, condition.execute().get(2, TimeUnit.SECONDS));
    }

    @Test
    public void pastUnparkTimeIsMetImmediately() throws Exception {
        executor = Executors.newScheduledThreadPool(1);

        // Build a condition for an unpark time that has already passed.
        DeadlineCondition condition = new DeadlineCondition(executor, ConditionType.UNPARK, Duration.ofMillis(-100));

        // The condition is met without waiting.
        assertSame(condition, condition.execute().get(1, TimeUnit.SECONDS));
    }

    @Test
    public void informNeverRequestsReplacement() {
        executor = Executors.newScheduledThreadPool(1);
        DeadlineCondition condition = new DeadlineCondition(executor, ConditionType.UNPARK, Duration.ofSeconds(120));

        // Inform the condition of a change set and of an interruption.
        boolean replaceAfterChangeSet = condition.inform(FDv2SourceResult.changeSet(
            new ChangeSet<>(ChangeSetType.None, Selector.EMPTY, null, null, true),
            false));
        boolean replaceAfterInterruption = condition.inform(FDv2SourceResult.interrupted(
            new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, 500, null, Instant.now()),
            false));

        // Neither result requires a new condition.
        assertFalse(replaceAfterChangeSet);
        assertFalse(replaceAfterInterruption);
    }

    @Test
    public void factoryBuildsConditionMetAtEarliestUnparkTime() throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        Instant unparkTime = Instant.now().plusMillis(50);
        DeadlineCondition.Factory factory =
            new DeadlineCondition.Factory(executor, ConditionType.UNPARK, () -> unparkTime);

        // Build a condition while a synchronizer is parked.
        Condition condition = factory.build();

        // The condition is met at the unpark time.
        assertEquals(ConditionType.UNPARK, factory.getType());
        assertNotNull(condition);
        CompletableFuture<Condition> future = condition.execute();
        assertSame(condition, future.get(2, TimeUnit.SECONDS));
    }

    @Test
    public void factoryReturnsNullWhenNoSynchronizerIsParked() {
        executor = Executors.newScheduledThreadPool(1);
        DeadlineCondition.Factory factory = new DeadlineCondition.Factory(executor, ConditionType.UNPARK, () -> null);

        // Build a condition while no synchronizer is parked.
        Condition condition = factory.build();

        // There is no condition.
        assertNull(condition);
    }
}
