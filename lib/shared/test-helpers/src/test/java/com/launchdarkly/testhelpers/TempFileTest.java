package com.launchdarkly.testhelpers;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@SuppressWarnings("javadoc")
public class TempFileTest {
  @Test
  public void tempFile() throws Exception {
    Path path = null;
    try (TempFile file = TempFile.create()) {
      path = file.getPath();
      assertThat(Files.isRegularFile(path), is(true));
      
      assertThat(new String(Files.readAllBytes(path)), equalTo(""));

      file.setContents("xyz");
      
      assertThat(new String(Files.readAllBytes(path)), equalTo("xyz"));
    }
    assertThat(Files.exists(path), is(false));
  }

  @Test
  public void tempFileWithSuffix() throws Exception {
    Path path = null;
    try (TempFile file = TempFile.create(".x")) {
      path = file.getPath();
      assertThat(Files.isRegularFile(path), is(true));
      assertThat(path.toString(), endsWith(".x"));
    }
    assertThat(Files.exists(path), is(false));
  }

  @Test
  public void canDeleteTempFileBeforeClosing() throws Exception {
    Path path = null;
    try (TempFile file = TempFile.create()) {
      path = file.getPath();
      assertThat(Files.isRegularFile(path), is(true));
     
      file.delete();

      assertThat(Files.exists(path), is(false));
    }
    assertThat(Files.exists(path), is(false));
  }
}
