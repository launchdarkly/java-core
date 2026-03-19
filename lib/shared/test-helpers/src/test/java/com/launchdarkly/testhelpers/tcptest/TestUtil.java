package com.launchdarkly.testhelpers.tcptest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.Charset;

@SuppressWarnings("javadoc")
public class TestUtil {
  public static boolean doesPortHaveListener(int port) {
    try {
      try (Socket s = new Socket("localhost", port)) {}
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  public static byte[] readStreamFully(InputStream input) throws IOException {
    return readStream(input, -1);
  }

  public static byte[] readStream(InputStream input, int maxLength) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[1000];
    while (true) {
      int n = input.read(buffer);
      if (n < 0) {
        break;
      }
      bytes.write(buffer, 0, n);
      if (maxLength > 0 && bytes.size() >= maxLength) {
        break;
      }
    }
    return bytes.toByteArray();
  }
  
  public static String toUtf8String(byte[] data) {
    return new String(data, Charset.forName("UTF-8"));
  }
  
  public static byte[] toUtf8Bytes(String s) {
    return s.getBytes(Charset.forName("UTF-8"));
  }
}
