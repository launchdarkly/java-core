package com.launchdarkly.testhelpers;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

@SuppressWarnings("javadoc")
public class TempDirTest {
  @Test
  public void tempDir() {
    Path path = null;
    try (TempDir dir = TempDir.create()) {
      path = dir.getPath();
      assertThat(Files.isDirectory(path), is(true));
      
      TempFile f1 = dir.tempFile();
      assertThat(Files.isRegularFile(f1.getPath()), is(true));
      assertThat(f1.getPath().toString(), startsWith(path.toString()));

      TempFile f2 = dir.tempFile(".x");
      assertThat(Files.isRegularFile(f2.getPath()), is(true));
      assertThat(f2.getPath().toString(), startsWith(path.toString()));
      assertThat(f2.getPath().toString(), endsWith(".x"));
    }
    assertThat(Files.exists(path), is(false));
  }

  @Test
  public void canDeleteTempDirBeforeClosing() {
    Path path = null;
    try (TempDir dir = TempDir.create()) {
      path = dir.getPath();
      assertThat(Files.isDirectory(path), is(true));
     
      dir.tempFile("");
      
      dir.delete();
      assertThat(Files.exists(path), is(false));
    }
    assertThat(Files.exists(path), is(false));
  }
}
