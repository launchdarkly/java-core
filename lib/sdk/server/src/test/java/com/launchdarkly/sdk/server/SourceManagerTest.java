package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.datasources.Synchronizer;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("javadoc")
public class SourceManagerTest extends BaseTest {

    private static class TestSynchronizerFactory extends SynchronizerFactoryWithState {
        private final FDv2DataSource.DataSourceFactory<Synchronizer> mockFactory;

        public TestSynchronizerFactory(FDv2DataSource.DataSourceFactory<Synchronizer> mockFactory) {
            super(mockFactory);
            this.mockFactory = mockFactory;
        }

        public FDv2DataSource.DataSourceFactory<Synchronizer> getFactory() {
            return mockFactory;
        }
    }

    private TestSynchronizerFactory createMockFactory() {
        FDv2DataSource.DataSourceFactory<Synchronizer> factory = mock(FDv2DataSource.DataSourceFactory.class);
        // Return a new mock each time build() is called to avoid reusing the same instance
        when(factory.build()).thenAnswer(invocation -> mock(Synchronizer.class));
        return new TestSynchronizerFactory(factory);
    }

    @Test
    public void getNextAvailableSynchronizerReturnsNullWhenEmpty() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        Synchronizer result = manager.getNextAvailableSynchronizerAndSetActive();

        assertNull(result);
    }

    @Test
    public void getNextAvailableSynchronizerReturnsFirstOnFirstCall() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        TestSynchronizerFactory sync1 = createMockFactory();
        synchronizers.add(sync1);

        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        Synchronizer result = manager.getNextAvailableSynchronizerAndSetActive();

        assertNotNull(result);
        // Verify it was built from sync1
        verify(sync1.getFactory(), times(1)).build();
    }

    @Test
    public void getNextAvailableSynchronizerLoopsThroughAvailable() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        TestSynchronizerFactory sync1 = createMockFactory();
        TestSynchronizerFactory sync2 = createMockFactory();
        TestSynchronizerFactory sync3 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);
        synchronizers.add(sync3);

        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        // First call builds from sync1
        manager.getNextAvailableSynchronizerAndSetActive();
        verify(sync1.getFactory(), times(1)).build();

        // Second call builds from sync2
        manager.getNextAvailableSynchronizerAndSetActive();
        verify(sync2.getFactory(), times(1)).build();

        // Third call builds from sync3
        manager.getNextAvailableSynchronizerAndSetActive();
        verify(sync3.getFactory(), times(1)).build();
    }

    @Test
    public void getNextAvailableSynchronizerWrapsAroundToBeginning() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        TestSynchronizerFactory sync1 = createMockFactory();
        TestSynchronizerFactory sync2 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);

        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        // Get all synchronizers
        manager.getNextAvailableSynchronizerAndSetActive(); // sync1
        manager.getNextAvailableSynchronizerAndSetActive(); // sync2

        // Should wrap around to sync1
        manager.getNextAvailableSynchronizerAndSetActive();

        // sync1 should have been built twice
        verify(sync1.getFactory(), times(2)).build();
    }

    @Test
    public void getNextAvailableSynchronizerSkipsBlockedSynchronizers() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        TestSynchronizerFactory sync1 = createMockFactory();
        TestSynchronizerFactory sync2 = createMockFactory();
        TestSynchronizerFactory sync3 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);
        synchronizers.add(sync3);

        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        // Block sync2
        sync2.block();

        // First call builds from sync1
        manager.getNextAvailableSynchronizerAndSetActive();
        verify(sync1.getFactory(), times(1)).build();

        // Second call skips sync2 and builds from sync3
        manager.getNextAvailableSynchronizerAndSetActive();
        verify(sync3.getFactory(), times(1)).build();
        verify(sync2.getFactory(), times(0)).build();

        // Third call wraps and builds from sync1 (skips sync2)
        manager.getNextAvailableSynchronizerAndSetActive();
        verify(sync1.getFactory(), times(2)).build();
    }

    @Test
    public void getNextAvailableSynchronizerReturnsNullWhenAllBlocked() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync1 = createMockFactory();
        SynchronizerFactoryWithState sync2 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);

        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        // Block all synchronizers
        sync1.block();
        sync2.block();

        Synchronizer result = manager.getNextAvailableSynchronizerAndSetActive();

        assertNull(result);
    }

    @Test
    public void resetSourceIndexResetsToFirstSynchronizer() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        TestSynchronizerFactory sync1 = createMockFactory();
        TestSynchronizerFactory sync2 = createMockFactory();
        TestSynchronizerFactory sync3 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);
        synchronizers.add(sync3);

        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        // Advance to sync3
        manager.getNextAvailableSynchronizerAndSetActive(); // sync1
        manager.getNextAvailableSynchronizerAndSetActive(); // sync2
        manager.getNextAvailableSynchronizerAndSetActive(); // sync3

        // Reset
        manager.resetSourceIndex();

        // Next call should build from sync1 again
        manager.getNextAvailableSynchronizerAndSetActive();
        verify(sync1.getFactory(), times(2)).build();
    }

    @Test
    public void isPrimeSynchronizerReturnsTrueForFirst() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        TestSynchronizerFactory sync1 = createMockFactory();
        TestSynchronizerFactory sync2 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);

        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        // Get first synchronizer
        manager.getNextAvailableSynchronizerAndSetActive();

        assertTrue(manager.isPrimeSynchronizer());
    }

    @Test
    public void isPrimeSynchronizerReturnsFalseForNonFirst() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        TestSynchronizerFactory sync1 = createMockFactory();
        TestSynchronizerFactory sync2 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);

        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        // Get first then second synchronizer
        manager.getNextAvailableSynchronizerAndSetActive();
        manager.getNextAvailableSynchronizerAndSetActive();

        assertFalse(manager.isPrimeSynchronizer());
    }

    @Test
    public void isPrimeSynchronizerReturnsFalseWhenNoSynchronizerSelected() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync1 = createMockFactory();
        synchronizers.add(sync1);

        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        // Haven't called getNext yet
        assertFalse(manager.isPrimeSynchronizer());
    }

    @Test
    public void isPrimeSynchronizerHandlesBlockedFirstSynchronizer() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        TestSynchronizerFactory sync1 = createMockFactory();
        TestSynchronizerFactory sync2 = createMockFactory();
        TestSynchronizerFactory sync3 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);
        synchronizers.add(sync3);

        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        // Block first synchronizer
        sync1.block();

        // Get second synchronizer (which is now the prime)
        manager.getNextAvailableSynchronizerAndSetActive();

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

        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

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

        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        assertEquals(3, manager.getAvailableSynchronizerCount());

        sync2.block();
        assertEquals(2, manager.getAvailableSynchronizerCount());

        sync1.block();
        assertEquals(1, manager.getAvailableSynchronizerCount());

        sync3.block();
        assertEquals(0, manager.getAvailableSynchronizerCount());
    }


    @Test
    public void shutdownClosesActiveSource() throws IOException {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync = createMockFactory();
        synchronizers.add(sync);
        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        Synchronizer source = manager.getNextAvailableSynchronizerAndSetActive();
        assertNotNull(source);

        manager.close();

        verify(source, times(1)).close();
    }

    @Test
    public void shutdownCanBeCalledMultipleTimes() throws IOException {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync = createMockFactory();
        synchronizers.add(sync);
        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        Synchronizer source = manager.getNextAvailableSynchronizerAndSetActive();
        assertNotNull(source);

        manager.close();
        manager.close();
        manager.close();

        // Should only close once
        verify(source, times(1)).close();
    }

    @Test
    public void shutdownIgnoresCloseException() throws IOException {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SynchronizerFactoryWithState sync = createMockFactory();
        synchronizers.add(sync);
        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        Synchronizer source = manager.getNextAvailableSynchronizerAndSetActive();
        assertNotNull(source);
        doThrow(new IOException("test")).when(source).close();

        // Should not throw
        manager.close();
    }

    @Test
    public void shutdownWithoutActiveSourceDoesNotFail() {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        // Should not throw
        manager.close();
    }

    @Test
    public void integrationTestFullCycle() throws IOException {
        List<SynchronizerFactoryWithState> synchronizers = new ArrayList<>();
        TestSynchronizerFactory sync1 = createMockFactory();
        TestSynchronizerFactory sync2 = createMockFactory();
        TestSynchronizerFactory sync3 = createMockFactory();
        synchronizers.add(sync1);
        synchronizers.add(sync2);
        synchronizers.add(sync3);

        SourceManager manager = new SourceManager(synchronizers, new ArrayList<>());

        // Initial state
        assertEquals(3, manager.getAvailableSynchronizerCount());
        assertFalse(manager.isPrimeSynchronizer());

        // Get first synchronizer
        Synchronizer first = manager.getNextAvailableSynchronizerAndSetActive();
        assertNotNull(first);
        verify(sync1.getFactory(), times(1)).build();
        assertTrue(manager.isPrimeSynchronizer());

        // Get second synchronizer
        Synchronizer second = manager.getNextAvailableSynchronizerAndSetActive();
        assertNotNull(second);
        verify(sync2.getFactory(), times(1)).build();
        assertFalse(manager.isPrimeSynchronizer());

        // Block second
        sync2.block();
        assertEquals(2, manager.getAvailableSynchronizerCount());

        // Get third synchronizer
        Synchronizer third = manager.getNextAvailableSynchronizerAndSetActive();
        assertNotNull(third);
        verify(sync3.getFactory(), times(1)).build();
        assertFalse(manager.isPrimeSynchronizer());

        // Reset and get first again
        manager.resetSourceIndex();
        Synchronizer firstAgain = manager.getNextAvailableSynchronizerAndSetActive();
        assertNotNull(firstAgain);
        verify(sync1.getFactory(), times(2)).build();
        assertTrue(manager.isPrimeSynchronizer());

        // Verify latest active source is set
        Synchronizer source = manager.getNextAvailableSynchronizerAndSetActive();
        assertNotNull(source);

        // Shutdown
        manager.close();
        verify(source, times(1)).close();

        // After shutdown, new sources are immediately closed
        Synchronizer newSource = manager.getNextAvailableSynchronizerAndSetActive();
        assertNull(newSource); // Returns null after shutdown
    }
}
