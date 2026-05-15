package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.FDv2DataSourceConditions.Condition;
import com.launchdarkly.sdk.server.FDv2DataSourceConditions.Condition.ConditionType;
import com.launchdarkly.sdk.server.FDv2DataSourceConditions.FallbackCondition;
import com.launchdarkly.sdk.server.FDv2DataSourceConditions.RecoveryCondition;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Direct tests for {@link FDv2DataSource.Conditions}.
 *
 * <p>The Conditions class is the aggregator that races fallback/recovery
 * condition futures against synchronizer.next() in the FDv2DataSource run
 * loop. Each iteration of that loop calls getFuture() and passes the result to
 * CompletableFuture.anyOf(...) -- so getFuture() must not return a shared
 * instance, or every anyOf call permanently attaches a Completion node to the
 * shared instance's stack, leaking memory proportional to event rate during
 * the synchronizer's tenure on a healthy primary.
 */
public class FDv2DataSourceConditionsAggregateTest {
    private ScheduledExecutorService executor;

    @Before
    public void setUp() {
        executor = Executors.newScheduledThreadPool(1);
    }

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    /**
     * Bug-proving test: getFuture() must return a fresh instance per call.
     *
     * <p>If it returns the same instance (as it did before the fix), the run
     * loop's per-iteration {@code anyOf(getFuture(), syncNext)} attaches a new
     * OrRelay Completion to the shared future's stack every iteration, with no
     * deregister path -- a monotonic leak for a non-firing aggregate.
     */
    @Test
    public void getFutureReturnsDistinctInstancesPerCall() {
        Condition fallback = new FallbackCondition(executor, 60);
        try (FDv2DataSource.Conditions conditions =
                 new FDv2DataSource.Conditions(Collections.singletonList(fallback))) {
            CompletableFuture<Object> f1 = conditions.getFuture();
            CompletableFuture<Object> f2 = conditions.getFuture();
            CompletableFuture<Object> f3 = conditions.getFuture();
            assertThat(f1, not(sameInstance(f2)));
            assertThat(f2, not(sameInstance(f3)));
            assertThat(f1, not(sameInstance(f3)));
        }
    }

    /**
     * Even with no underlying conditions (a single-synchronizer configuration),
     * getFuture() must return fresh instances. The aggregate never completes
     * in this case, which is exactly the scenario where any per-iteration
     * accumulation would be most damaging.
     */
    @Test
    public void getFutureReturnsDistinctInstancesEvenWithNoConditions() {
        try (FDv2DataSource.Conditions conditions =
                 new FDv2DataSource.Conditions(Collections.emptyList())) {
            CompletableFuture<Object> f1 = conditions.getFuture();
            CompletableFuture<Object> f2 = conditions.getFuture();
            assertThat(f1, not(sameInstance(f2)));
        }
    }

    /**
     * Every fresh future returned by getFuture() must complete when the
     * underlying aggregate fires. The fan-out via the single permanent listener
     * is what makes the fresh-per-call pattern work; verify it actually
     * delivers.
     */
    @Test
    public void allFreshFuturesCompleteWhenAggregateFires() throws Exception {
        // 0-second timeout -> fires on first INTERRUPTED inform.
        Condition fallback = new FallbackCondition(executor, 0);
        try (FDv2DataSource.Conditions conditions =
                 new FDv2DataSource.Conditions(Collections.singletonList(fallback))) {
            CompletableFuture<Object> f1 = conditions.getFuture();
            CompletableFuture<Object> f2 = conditions.getFuture();
            CompletableFuture<Object> f3 = conditions.getFuture();

            conditions.inform(makeInterruptedResult());

            Object r1 = f1.get(2, TimeUnit.SECONDS);
            Object r2 = f2.get(2, TimeUnit.SECONDS);
            Object r3 = f3.get(2, TimeUnit.SECONDS);

            assertNotNull(r1);
            assertNotNull(r2);
            assertNotNull(r3);
            assertTrue(r1 instanceof Condition);
            assertTrue(r2 instanceof Condition);
            assertTrue(r3 instanceof Condition);
        }
    }

    /**
     * Bug-proving test for the null-sentinel issue caught by Cursor Bugbot:
     * if the underlying aggregate completes <em>exceptionally</em>, every
     * future returned by getFuture() -- both those handed out before the
     * exception and those requested after -- must complete exceptionally too.
     *
     * <p>Prior to this fix, the "fired" state was tracked via a {@code null}
     * sentinel on a {@code completedValue} field, which also stayed
     * {@code null} on exceptional completion. A subsequent getFuture() call
     * would then return {@code CompletableFuture.completedFuture(null)} --
     * silently converting an exceptional completion into a normal
     * {@code null} completion. The run loop's downstream
     * {@code res.getClass().getName()} would then throw NPE.
     */
    @Test
    public void getFutureFailsExceptionallyWhenAggregateFailsExceptionally()
            throws Exception {
        ManualCondition manualCondition = new ManualCondition();
        try (FDv2DataSource.Conditions conditions =
                 new FDv2DataSource.Conditions(Collections.singletonList(manualCondition))) {
            // Future requested BEFORE the exceptional completion.
            CompletableFuture<Object> before = conditions.getFuture();

            RuntimeException boom = new RuntimeException("simulated condition failure");
            manualCondition.future.completeExceptionally(boom);

            // Future requested AFTER the exceptional completion (exercises the
            // fast path through makeCompletedFuture). This is the case bugbot
            // caught: pre-fix, it returned completedFuture(null).
            CompletableFuture<Object> after = conditions.getFuture();

            assertThrowsExecutionExceptionWithCause(before, boom);
            assertThrowsExecutionExceptionWithCause(after, boom);
        }
    }

    /**
     * getFuture() called after the aggregate has already fired returns an
     * already-completed future synchronously (the fast path).
     */
    @Test
    public void getFutureAfterAggregateFiresReturnsCompletedFuture() throws Exception {
        // RecoveryCondition arms its timer in the constructor and fires after
        // the configured timeout. With timeout=0 it fires near-immediately.
        Condition recovery = new RecoveryCondition(executor, 0);
        try (FDv2DataSource.Conditions conditions =
                 new FDv2DataSource.Conditions(Collections.singletonList(recovery))) {
            // Drain a future to confirm the aggregate has fired.
            conditions.getFuture().get(2, TimeUnit.SECONDS);

            CompletableFuture<Object> postFire = conditions.getFuture();
            assertTrue("post-fire getFuture() should be already complete", postFire.isDone());
            assertNotNull(postFire.get(0, TimeUnit.SECONDS));
        }
    }

    private static FDv2SourceResult makeInterruptedResult() {
        return FDv2SourceResult.interrupted(
            new DataSourceStatusProvider.ErrorInfo(
                DataSourceStatusProvider.ErrorKind.NETWORK_ERROR,
                0,
                "simulated",
                Instant.now()),
            false);
    }

    /**
     * Asserts that {@code future.get()} throws {@link ExecutionException}
     * wrapping the expected cause. {@code CompletableFuture#get} surfaces
     * exceptional completion as ExecutionException with the original
     * exception as its cause.
     */
    private static void assertThrowsExecutionExceptionWithCause(
            CompletableFuture<Object> future,
            Throwable expectedCause) throws Exception {
        try {
            future.get(2, TimeUnit.SECONDS);
            throw new AssertionError("expected ExecutionException, got normal completion");
        } catch (ExecutionException ee) {
            if (ee.getCause() != expectedCause) {
                throw new AssertionError(
                    "expected cause to be " + expectedCause + " but was " + ee.getCause(), ee);
            }
        }
    }

    /**
     * Test-only Condition with an externally-controllable future. The
     * existing FallbackCondition/RecoveryCondition only resolve normally
     * (with {@code this}); to exercise the exceptional path through the
     * aggregate's whenComplete listener we need a Condition we can fail
     * directly.
     */
    private static final class ManualCondition
            implements Condition {
        final CompletableFuture<Condition> future = new CompletableFuture<>();

        @Override
        public CompletableFuture<Condition> execute() {
            return future;
        }

        @Override
        public void inform(FDv2SourceResult sourceResult) {
            // Manually controlled; no auto-trigger from inform.
        }

        @Override
        public void close() {
            // No timer to cancel.
        }

        @Override
        public ConditionType getType() {
            return ConditionType.FALLBACK;
        }
    }
}
