package com.launchdarkly.testhelpers.tcptest;

import java.io.IOException;
import java.net.Socket;

/**
 * Use with {@link TcpServer} to define behavior for a TCP endpoint in a test.
 * 
 * @since 1.3.0
 */
public interface TcpHandler {
  /**
   * Processes the request.
   * 
   * @param socket the incoming socket
   * @throws IOException for any I/O error
   */
  void apply(Socket socket) throws IOException;
}
