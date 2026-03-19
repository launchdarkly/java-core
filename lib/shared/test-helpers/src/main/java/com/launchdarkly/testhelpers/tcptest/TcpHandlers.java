package com.launchdarkly.testhelpers.tcptest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Factory methods for standard {@link TcpHandler} implementations.
 * 
 * @since 1.3.0
 */
public abstract class TcpHandlers {
  private TcpHandlers() {}

  /**
   * Creates an implementation of {@link TcpHandler} that writes some data to the socket.
   * 
   * @param data the data buffer
   * @param offset the starting offset
   * @param length the number of bytes to write
   * @return a handler
   */
  public static TcpHandler writeData(final byte[] data, final int offset, final int length) {
    return new TcpHandler() {
      @Override
      public void apply(Socket socket) throws IOException {
        socket.getOutputStream().write(data, offset, length);
      }
    };
  }

  /**
   * Creates an implementation of {@link TcpHandler} that writes a UTF-8 string to the socket.
   * 
   * @param s the string
   * @return a handler
   */
  public static TcpHandler writeString(String s) {
    byte[] data = s.getBytes(Charset.forName("UTF-8"));
    return writeData(data, 0, data.length);
  }
  
  /**
   * Creates an implementation of {@link TcpHandler} that, for each incoming request, opens
   * a socket connection to the specified port and then forwards all traffic from the incoming
   * request to that socket, and vice versa.
   * 
   * @param forwardToPort the port to forward to
   * @return a handler
   */
  public static TcpHandler forwardToPort(final int forwardToPort) {
    return new TcpHandler() {
      @Override
      public void apply(Socket incomingSocket) throws IOException {
        InputStream incomingSocketRead = incomingSocket.getInputStream();
        OutputStream incomingSocketWrite = incomingSocket.getOutputStream();
        try (Socket forwardedSocket = new Socket(incomingSocket.getInetAddress().getHostAddress(), forwardToPort)) {
          InputStream forwardedSocketRead = forwardedSocket.getInputStream();
          OutputStream forwardedSocketWrite = forwardedSocket.getOutputStream();
          final CountDownLatch closeSignal = new CountDownLatch(1);
          new Thread(newForwarder(incomingSocketRead, forwardedSocketWrite, closeSignal)).start();
          new Thread(newForwarder(forwardedSocketRead, incomingSocketWrite, closeSignal)).start();
          try {
            closeSignal.await();
          } catch (InterruptedException e) {}
        }
      }
    };
  }
  
  private static Runnable newForwarder(InputStream fromStream, OutputStream toStream, CountDownLatch closeSignal) {
    return new Runnable() {
      @Override
      public void run() {
        byte[] buffer = new byte[1000];
        while (true) {
          try {
            int n = fromStream.read(buffer);
            if (n < 0) {
               break;
            }
            toStream.write(buffer, 0, n);
            toStream.flush();
          } catch (IOException e) {
            break;
          }
        }
        closeSignal.countDown();
      }
    };
  }
  
  /**
   * Returns an implementation of {@link TcpHandler} that immediately exits, so that
   * {@link TcpServer} will close the socket with no response.
   * <p>
   * A typical use case would be to simulate an I/O error when testing client code that is
   * making HTTP requests; if the (simulated) HTTP server closes the socket without writing
   * a response, clients will treat this as a broken connection error.
   * 
   * @return a handler
   */
  public static TcpHandler noResponse() {
    return new TcpHandler() {
      @Override
      public void apply(Socket socket) {}
    };
  }
  
  /**
   * Creates a stateful {@link TcpHandler} that delegates to each of the specified handlers in sequence
   * as each request is received.
   * 
   * @param handlers a sequence of handlers
   * @return a handler
   */
  public static TcpHandler sequential(TcpHandler... handlers) {
    final AtomicInteger index = new AtomicInteger(0);
    final TcpHandler[] h = Arrays.copyOf(handlers, handlers.length);

    return new TcpHandler() {
      @Override
      public void apply(Socket socket) throws IOException {
        int i = index.getAndIncrement();
        if (i >= h.length) {
          throw new RuntimeException("received more requests than the number of configured handlers");
        }
        h[i].apply(socket);
      }
    };
  }
}
