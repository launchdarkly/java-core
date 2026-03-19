package com.launchdarkly.testhelpers.tcptest;

import com.launchdarkly.testhelpers.httptest.HttpServer;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Date;

/**
 * A simple mechanism for creating a TCP listener and configuring its behavior.
 * <p>
 * This is analogous to {@link HttpServer}, but much simpler since it has no knowledge of
 * any particular protocol that might be used over TCP. See {@link TcpHandlers} for examples
 * of configurable behavior.
 * 
 * @since 1.3.0
 */
public class TcpServer implements Closeable {
  private final ServerSocket listener;
  private final int listenerPort;
  
  /**
   * Starts a new TCP test server on a specific port.
   * 
   * @param port the port to listen on
   * @param handler a {@link TcpHandler} implementation
   * @return a server
   */
  public static TcpServer start(int port, TcpHandler handler) {
    return new TcpServer(port, handler);
  }

  /**
   * Starts a new TCP test server on any available port.
   * 
   * @param handler a {@link TcpHandler} implementation
   * @return a server
   */
  public static TcpServer start(TcpHandler handler) {
    return new TcpServer(0, handler);
  }

  TcpServer(int port, final TcpHandler handler) {
    try {
      listener = new ServerSocket(port);
    } catch (IOException e) {
      throw new RuntimeException("unable to create TCP listener", e);
    }
    listenerPort = port == 0 ? listener.getLocalPort() : port;

    new Thread(new Runnable() {
      @Override
      public void run() {
        while (true) {
          final Socket socket;
          try {
            socket = listener.accept();
          } catch (IOException e) {
            // almost certainly means we closed the socket
            return;
          }
          new Thread(new Runnable() {
            @Override
            public void run() {
              try {
                handler.apply(socket);
              } catch (Exception e) {
                logError("handler threw exception: " + e);
              }
              try {
                socket.close();
              } catch (IOException e) {
                logError("failed to close socket: " + e);
              }
            }
          }).run();
        }
      }
    }).start();
  }
  
  @Override
  public void close() {
    try {
      listener.close();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
  
  /**
   * Returns the port we are listening on.
   * 
   * @return the port
   */
  public int getPort() {
    return listenerPort;
  }

  /**
   * Convenience method for constructing an HTTP URI with the listener port. This does not
   * mean the listener necessarily can accept HTTP requests, but it may be useful if for
   * instance you have configured it with {@link TcpHandlers#forwardToPort(int)} to forward
   * requests to an {@link HttpServer}.
   * 
   * @return an HTTP URI using localhost and the value of {@link #getPort()}
   */
  public URI getHttpUri() {
    return URI.create("http://localhost:" + listenerPort);
  }
  
  private void logError(String message) {
    System.err.println("TcpServer [" + new Date() + "]: " + message);
  }
}
