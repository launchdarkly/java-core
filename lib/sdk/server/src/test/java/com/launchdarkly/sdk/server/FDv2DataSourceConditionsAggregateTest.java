package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.FDv2DataSourceConditions.Condition;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
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

    /**
     * Bug-proving test for the underlying leak: repeated getFuture() calls
     * whose returned futures are then dropped (the run-loop pattern: each
     * iteration's anyOf result becomes garbage at end of iteration) must NOT
     * cause the pending list to grow without bound. The opportunistic prune
     * inside getFuture() collects entries whose WeakReference target has been
     * collected.
     *
     * <p>Java does not guarantee that {@link System#gc()} actually runs, but
     * in practice with HotSpot's default GC plus a brief sleep this is
     * reliable. If it ever flakes on CI, increase the iteration count or the
     * sleep, or migrate to a {@code -XX:+UseSerialGC} test profile.
     */
    @Test
    public void pendingListDoesNotGrowUnboundedlyWhenFreshFuturesAreDropped()
            throws Exception {
        Condition fallback = new FallbackCondition(executor, 60); // never fires
        try (FDv2DataSource.Conditions conditions =
                 new FDv2DataSource.Conditions(Collections.singletonList(fallback))) {
            int iterations = 10_000;
            for (int i = 0; i < iterations; i++) {
                CompletableFuture<Object> f = conditions.getFuture();
                // Simulate the run loop: race f against a fast-resolving sibling.
                // The anyOf result is awaited and discarded; f becomes unreachable
                // at end of iteration.
                CompletableFuture<Object> sibling = CompletableFuture.completedFuture("ok");
                CompletableFuture.anyOf(f, sibling).get(1, TimeUnit.SECONDS);
                // f goes out of scope here.

                // Periodically encourage GC + give the cleanup path a chance.
                if (i % 1000 == 999) {
                    System.gc();
                    Thread.sleep(10);
                }
            }
            System.gc();
            Thread.sleep(50);

            // After 10k iterations, pendingSize() should not be anywhere near
            // 10k. The opportunistic prune inside getFuture() runs on every
            // call, so any entry whose WeakReference has been collected drops
            // out. A small handful (< 100) of recently-added live refs is
            // expected because the most recent iterations may not yet have
            // been GC'd. Choose a generous ceiling to avoid CI flakiness while
            // still being orders of magnitude below the pre-fix accumulation.
            int finalSize = conditions.pendingSize();
            assertThat(
                "pending list size should be bounded; was " + finalSize
                    + " after " + iterations + " iterations",
                finalSize, lessThanOrEqualTo(500));
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
}
