package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.datasources.Synchronizer;

import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("javadoc")
public class SynchronizerStateManagerTest extends BaseTest {

    private SynchronizerFactoryWithState createMockFactory() {
        FDv2DataSource.DataSourceFactory<Synchronizer> factory = mock(FDv2DataSource.DataSourceFactory.class);
        when(factory.build()).thenReturn(mock(Synchronizer.class));
        return new SynchronizerFactoryWithState(factory);
    }

    @Test
    public void getNextAvailableSynchronizerReturnsNullWhenEmpty() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        SynchronizerFactoryWithState result = manager.getNextAvailableSynchronizer();

        assertNull(result);
    }

    @Test
    public void getNextAvailableSynchronizerReturnsFirstOnFirstCall() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync1 = createMockFactory();
        synchronizers.add(sync1);

        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        SynchronizerFactoryWithState result = manager.getNextAvailableSynchronizer();

        assertSame(sync1, result);
    }

    @Test
    public void getNextAvailableSynchronizerLoopsThroughAvailable() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync1 = createMockFactory();
        SynchronizerFactoryWithState sync2 = createMockFactory();
        SynchronizerFactoryWithState sync3 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);
        synchronizers.add(sync3);

        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        // First call returns sync1
        assertSame(sync1, manager.getNextAvailableSynchronizer());
        // Second call returns sync2
        assertSame(sync2, manager.getNextAvailableSynchronizer());
        // Third call returns sync3
        assertSame(sync3, manager.getNextAvailableSynchronizer());
    }

    @Test
    public void getNextAvailableSynchronizerWrapsAroundToBeginning() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync1 = createMockFactory();
        SynchronizerFactoryWithState sync2 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);

        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        // Get all synchronizers
        manager.getNextAvailableSynchronizer(); // sync1
        manager.getNextAvailableSynchronizer(); // sync2

        // Should wrap around to sync1
        assertSame(sync1, manager.getNextAvailableSynchronizer());
    }

    @Test
    public void getNextAvailableSynchronizerSkipsBlockedSynchronizers() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync1 = createMockFactory();
        SynchronizerFactoryWithState sync2 = createMockFactory();
        SynchronizerFactoryWithState sync3 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);
        synchronizers.add(sync3);

        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        // Block sync2
        sync2.block();

        // First call returns sync1
        assertSame(sync1, manager.getNextAvailableSynchronizer());
        // Second call skips sync2 and returns sync3
        assertSame(sync3, manager.getNextAvailableSynchronizer());
        // Third call wraps and returns sync1 (skips sync2)
        assertSame(sync1, manager.getNextAvailableSynchronizer());
    }

    @Test
    public void getNextAvailableSynchronizerReturnsNullWhenAllBlocked() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync1 = createMockFactory();
        SynchronizerFactoryWithState sync2 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);

        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        // Block all synchronizers
        sync1.block();
        sync2.block();

        SynchronizerFactoryWithState result = manager.getNextAvailableSynchronizer();

        assertNull(result);
    }

    @Test
    public void resetSourceIndexResetsToFirstSynchronizer() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync1 = createMockFactory();
        SynchronizerFactoryWithState sync2 = createMockFactory();
        SynchronizerFactoryWithState sync3 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);
        synchronizers.add(sync3);

        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        // Advance to sync3
        manager.getNextAvailableSynchronizer(); // sync1
        manager.getNextAvailableSynchronizer(); // sync2
        manager.getNextAvailableSynchronizer(); // sync3

        // Reset
        manager.resetSourceIndex();

        // Next call should return sync1 again
        assertSame(sync1, manager.getNextAvailableSynchronizer());
    }

    @Test
    public void isPrimeSynchronizerReturnsTrueForFirst() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync1 = createMockFactory();
        SynchronizerFactoryWithState sync2 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);

        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        // Get first synchronizer
        manager.getNextAvailableSynchronizer();

        assertTrue(manager.isPrimeSynchronizer());
    }

    @Test
    public void isPrimeSynchronizerReturnsFalseForNonFirst() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync1 = createMockFactory();
        SynchronizerFactoryWithState sync2 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);

        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        // Get first then second synchronizer
        manager.getNextAvailableSynchronizer();
        manager.getNextAvailableSynchronizer();

        assertFalse(manager.isPrimeSynchronizer());
    }

    @Test
    public void isPrimeSynchronizerReturnsFalseWhenNoSynchronizerSelected() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync1 = createMockFactory();
        synchronizers.add(sync1);

        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        // Haven't called getNext yet
        assertFalse(manager.isPrimeSynchronizer());
    }

    @Test
    public void isPrimeSynchronizerHandlesBlockedFirstSynchronizer() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync1 = createMockFactory();
        SynchronizerFactoryWithState sync2 = createMockFactory();
        SynchronizerFactoryWithState sync3 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);
        synchronizers.add(sync3);

        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        // Block first synchronizer
        sync1.block();

        // Get second synchronizer (which is now the prime)
        manager.getNextAvailableSynchronizer();

        assertTrue(manager.isPrimeSynchronizer());
    }

    @Test
    public void getAvailableSynchronizerCountReturnsCorrectCount() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync1 = createMockFactory();
        SynchronizerFactoryWithState sync2 = createMockFactory();
        SynchronizerFactoryWithState sync3 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);
        synchronizers.add(sync3);

        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        assertEquals(3, manager.getAvailableSynchronizerCount());
    }

    @Test
    public void getAvailableSynchronizerCountUpdatesWhenBlocked() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync1 = createMockFactory();
        SynchronizerFactoryWithState sync2 = createMockFactory();
        SynchronizerFactoryWithState sync3 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);
        synchronizers.add(sync3);

        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        assertEquals(3, manager.getAvailableSynchronizerCount());

        sync2.block();
        assertEquals(2, manager.getAvailableSynchronizerCount());

        sync1.block();
        assertEquals(1, manager.getAvailableSynchronizerCount());

        sync3.block();
        assertEquals(0, manager.getAvailableSynchronizerCount());
    }

    @Test
    public void setActiveSourceSetsNewSource() throws IOException {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        Closeable source = mock(Closeable.class);
        boolean shutdown = manager.setActiveSource(source);

        assertFalse(shutdown);
    }

    @Test
    public void setActiveSourceClosesPreviousSource() throws IOException {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        Closeable firstSource = mock(Closeable.class);
        Closeable secondSource = mock(Closeable.class);

        manager.setActiveSource(firstSource);
        manager.setActiveSource(secondSource);

        verify(firstSource, times(1)).close();
    }

    @Test
    public void setActiveSourceReturnsTrueAfterShutdown() throws IOException {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        manager.close();

        Closeable source = mock(Closeable.class);
        boolean shutdown = manager.setActiveSource(source);

        assertTrue(shutdown);
        verify(source, times(1)).close();
    }

    @Test
    public void setActiveSourceIgnoresCloseExceptionFromPreviousSource() throws IOException {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        Closeable firstSource = mock(Closeable.class);
        doThrow(new IOException("test")).when(firstSource).close();

        Closeable secondSource = mock(Closeable.class);

        manager.setActiveSource(firstSource);
        // Should not throw
        manager.setActiveSource(secondSource);
    }

    @Test
    public void shutdownClosesActiveSource() throws IOException {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        Closeable source = mock(Closeable.class);
        manager.setActiveSource(source);

        manager.close();

        verify(source, times(1)).close();
    }

    @Test
    public void shutdownCanBeCalledMultipleTimes() throws IOException {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        Closeable source = mock(Closeable.class);
        manager.setActiveSource(source);

        manager.close();
        manager.close();
        manager.close();

        // Should only close once
        verify(source, times(1)).close();
    }

    @Test
    public void shutdownIgnoresCloseException() throws IOException {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        Closeable source = mock(Closeable.class);
        doThrow(new IOException("test")).when(source).close();

        manager.setActiveSource(source);

        // Should not throw
        manager.close();
    }

    @Test
    public void shutdownWithoutActiveSourceDoesNotFail() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        // Should not throw
        manager.close();
    }

    @Test
    public void integrationTestFullCycle() throws IOException {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync1 = createMockFactory();
        SynchronizerFactoryWithState sync2 = createMockFactory();
        SynchronizerFactoryWithState sync3 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);
        synchronizers.add(sync3);

        SynchronizerStateManager manager = new SynchronizerStateManager(synchronizers);

        // Initial state
        assertEquals(3, manager.getAvailableSynchronizerCount());
        assertFalse(manager.isPrimeSynchronizer());

        // Get first synchronizer
        SynchronizerFactoryWithState first = manager.getNextAvailableSynchronizer();
        assertSame(sync1, first);
        assertTrue(manager.isPrimeSynchronizer());

        // Get second synchronizer
        SynchronizerFactoryWithState second = manager.getNextAvailableSynchronizer();
        assertSame(sync2, second);
        assertFalse(manager.isPrimeSynchronizer());

        // Block second
        sync2.block();
        assertEquals(2, manager.getAvailableSynchronizerCount());

        // Get third synchronizer
        SynchronizerFactoryWithState third = manager.getNextAvailableSynchronizer();
        assertSame(sync3, third);
        assertFalse(manager.isPrimeSynchronizer());

        // Reset and get first again
        manager.resetSourceIndex();
        SynchronizerFactoryWithState firstAgain = manager.getNextAvailableSynchronizer();
        assertSame(sync1, firstAgain);
        assertTrue(manager.isPrimeSynchronizer());

        // Set active source
        Closeable source = mock(Closeable.class);
        assertFalse(manager.setActiveSource(source));

        // Shutdown
        manager.close();
        verify(source, times(1)).close();

        // After shutdown, new sources are immediately closed
        Closeable newSource = mock(Closeable.class);
        assertTrue(manager.setActiveSource(newSource));
        verify(newSource, times(1)).close();
    }
}
