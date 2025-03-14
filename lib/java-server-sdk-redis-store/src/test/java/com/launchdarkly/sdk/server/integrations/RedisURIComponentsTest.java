package com.launchdarkly.sdk.server.integrations;

import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RedisURIComponentsTest {
  @Test
  public void getPasswordForURIWithoutUserInfo() {
    assertNull(RedisURIComponents.getPassword(URI.create("redis://hostname:6379")));
  }

  @Test
  public void getPasswordForURIWithUsernameAndNoPassword() {
    assertNull(RedisURIComponents.getPassword(URI.create("redis://username@hostname:6379")));
  }

  @Test
  public void getPasswordForURIWithUsernameAndPassword() {
    assertEquals("secret", RedisURIComponents.getPassword(URI.create("redis://username:secret@hostname:6379")));
  }

  @Test
  public void getPasswordForURIWithPasswordAndNoUsername() {
    assertEquals("secret", RedisURIComponents.getPassword(URI.create("redis://:secret@hostname:6379")));
  }
  
  @Test
  public void getDBIndexForURIWithoutPath() {
    assertEquals(0, RedisURIComponents.getDBIndex(URI.create("redis://hostname:6379")));
  }
  
  @Test
  public void getDBIndexForURIWithRootPath() {
    assertEquals(0, RedisURIComponents.getDBIndex(URI.create("redis://hostname:6379/")));
  }

  @Test
  public void getDBIndexForURIWithNumberInPath() {
    assertEquals(2, RedisURIComponents.getDBIndex(URI.create("redis://hostname:6379/2")));
  }
}
