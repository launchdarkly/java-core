package com.launchdarkly.testhelpers.tcptest;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;

import static com.launchdarkly.testhelpers.tcptest.TestUtil.readStreamFully;
import static com.launchdarkly.testhelpers.tcptest.TestUtil.toUtf8Bytes;
import static com.launchdarkly.testhelpers.tcptest.TestUtil.toUtf8String;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

@SuppressWarnings("javadoc")
public class TcpHandlersTest {
  @Test
  public void writeData() throws IOException {
    byte[] expected = new byte[] { 100, 101, 102 };
    TcpHandler handler = TcpHandlers.writeData(expected, 0, expected.length);
    
    try (TcpServer server = TcpServer.start(handler)) {
      try (Socket s = new Socket("localhost", server.getPort())) {
        byte[] actual = readStreamFully(s.getInputStream());
        assertArrayEquals(expected, actual);
      }
    }
  }

  @Test
  public void writeString() throws IOException {
    String message = "hello";
    TcpHandler handler = TcpHandlers.writeString(message);
    
    try (TcpServer server = TcpServer.start(handler)) {
      try (Socket s = new Socket("localhost", server.getPort())) {
        assertEquals(message, toUtf8String(readStreamFully(s.getInputStream())));
      }
    }
  }
  
  @Test
  public void forwardToPort() throws IOException {
    String question = "question?";
    String answer = "answer!";
    ByteArrayOutputStream receivedData = new ByteArrayOutputStream();
    TcpHandler handler = new TcpHandler() {
      @Override
      public void apply(Socket socket) throws IOException {
        byte[] data = TestUtil.readStream(socket.getInputStream(), toUtf8Bytes(question).length);
        receivedData.write(data);
        TcpHandlers.writeString(answer).apply(socket);
      }
    };
    
    try (TcpServer underlyingServer = TcpServer.start(handler)) {
      try (TcpServer forwardingServer = TcpServer.start(TcpHandlers.forwardToPort(underlyingServer.getPort()))) {
        try (Socket s = new Socket("localhost", forwardingServer.getPort())) {
          TcpHandlers.writeString(question).apply(s);
          assertEquals(answer, toUtf8String(readStreamFully(s.getInputStream())));
        }
      }
    }
  }
  
  @Test
  public void noResponse() throws IOException {
    TcpHandler handler = TcpHandlers.noResponse();
    
    try (TcpServer server = TcpServer.start(handler)) {
      try (Socket s = new Socket("localhost", server.getPort())) {
        byte[] data = readStreamFully(s.getInputStream());
        assertEquals(0, data.length);
      }
    }
  }
  
  @Test
  public void sequential() throws IOException {
    String message = "hello";
    TcpHandler handler = TcpHandlers.sequential(
        TcpHandlers.noResponse(),
        TcpHandlers.writeString(message));
    
    try (TcpServer server = TcpServer.start(handler)) {
      try (Socket s1 = new Socket("localhost", server.getPort())) {
        assertEquals("", toUtf8String(readStreamFully(s1.getInputStream())));
      }
      try (Socket s2 = new Socket("localhost", server.getPort())) {
        assertEquals(message, toUtf8String(readStreamFully(s2.getInputStream())));
      }
    }
  }
}
