package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.ComponentsImpl.EventProcessorBuilderImpl.EventSenderWrapper;
import com.launchdarkly.sdk.server.subsystems.EventSender;

import org.junit.Test;

import java.io.IOException;
import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class EventSenderWrapperTest {
  private static final URI FAKE_URI = URI.create("http://fake");
  private static final byte[] FAKE_DATA = new byte[] {1, 2, 3};

  private static final class RecordingSender implements EventSender {
    private final Result result;
    String methodCalled;
    byte[] receivedData;
    int receivedEventCount = -1;
    URI receivedUri;

    RecordingSender(Result result) {
      this.result = result;
    }

    @Override
    public Result sendAnalyticsEvents(byte[] data, int eventCount, URI eventsBaseUri) {
      methodCalled = "analytics";
      receivedData = data;
      receivedEventCount = eventCount;
      receivedUri = eventsBaseUri;
      return result;
    }

    @Override
    public Result sendDiagnosticEvent(byte[] data, URI eventsBaseUri) {
      methodCalled = "diagnostic";
      receivedData = data;
      receivedUri = eventsBaseUri;
      return result;
    }

    @Override
    public void close() throws IOException {}
  }

  @Test
  public void successMapsToSuccessfulDelivery() {
    RecordingSender sender = new RecordingSender(EventSender.Result.SUCCESS);
    com.launchdarkly.sdk.internal.events.EventSender.Result result =
        new EventSenderWrapper(sender).sendAnalyticsEvents(FAKE_DATA, 3, FAKE_URI);

    assertTrue(result.isSuccess());
    assertFalse(result.isUnexpectedFailure());
    assertNull(result.getTimeFromServer());

    // The wrapper routes to the analytics method with the arguments unchanged.
    assertEquals("analytics", sender.methodCalled);
    assertSame(FAKE_DATA, sender.receivedData);
    assertEquals(3, sender.receivedEventCount);
    assertSame(FAKE_URI, sender.receivedUri);
  }

  @Test
  public void failureMapsToOrdinaryFailure() {
    RecordingSender sender = new RecordingSender(EventSender.Result.FAILURE);
    com.launchdarkly.sdk.internal.events.EventSender.Result result =
        new EventSenderWrapper(sender).sendAnalyticsEvents(FAKE_DATA, 1, FAKE_URI);

    assertFalse(result.isSuccess());
    assertFalse(result.isUnexpectedFailure());
  }

  @SuppressWarnings("deprecation")
  @Test
  public void stopMapsToUnexpectedFailure() {
    RecordingSender sender = new RecordingSender(EventSender.Result.STOP);
    com.launchdarkly.sdk.internal.events.EventSender.Result result =
        new EventSenderWrapper(sender).sendAnalyticsEvents(FAKE_DATA, 1, FAKE_URI);

    assertFalse(result.isSuccess());
    assertTrue(result.isUnexpectedFailure());
    // The legacy flag stays written for consumers that have not migrated off it.
    assertTrue(result.isMustShutDown());
  }

  @Test
  public void diagnosticPathUsesTheSameMappingAndRouting() {
    RecordingSender stopSender = new RecordingSender(EventSender.Result.STOP);
    com.launchdarkly.sdk.internal.events.EventSender.Result stopResult =
        new EventSenderWrapper(stopSender).sendDiagnosticEvent(FAKE_DATA, FAKE_URI);
    assertTrue(stopResult.isUnexpectedFailure());
    assertEquals("diagnostic", stopSender.methodCalled);
    assertSame(FAKE_DATA, stopSender.receivedData);
    assertSame(FAKE_URI, stopSender.receivedUri);

    RecordingSender successSender = new RecordingSender(EventSender.Result.SUCCESS);
    assertTrue(new EventSenderWrapper(successSender)
        .sendDiagnosticEvent(FAKE_DATA, FAKE_URI).isSuccess());
    assertEquals("diagnostic", successSender.methodCalled);
  }
}
