package com.launchdarkly.testhelpers.tcptest;

import org.junit.Test;

import java.io.IOException;
import java.net.Socket;

import static com.launchdarkly.testhelpers.tcptest.TestUtil.doesPortHaveListener;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public class TcpServerTest {
  @Test
  public void listensOnAnyAvailablePort() throws IOException {
    int port;
    try (TcpServer server = TcpServer.start(TcpHandlers.noResponse())) {
      assertNotEquals(0, server.getPort());
      port = server.getPort();
      try (Socket s = new Socket("localhost", server.getPort())) {} // just verify that we can connect
    }
    assertPortReleased(port);
  }

  @Test
  public void listensOnSpecificPort() throws IOException {
    int specificPort = 10000;
    while (doesPortHaveListener(specificPort)) {
      if (specificPort == 65535) {
        fail("test could not find an available port");
      }
      specificPort++; 
    }
    try (TcpServer server = TcpServer.start(specificPort, TcpHandlers.noResponse())) {
      assertEquals(specificPort, server.getPort());
      try (Socket s = new Socket("localhost", specificPort)) {} // just verify that we can connect
    }
    assertPortReleased(specificPort);
  }

  private static void assertPortReleased(int port) {
    long deadline = System.currentTimeMillis() + 1000;
    while (System.currentTimeMillis() < deadline) {
      if (!doesPortHaveListener(port)) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    assertFalse("expected listener to be closed, but it wasn't", doesPortHaveListener(port));
  }
}
