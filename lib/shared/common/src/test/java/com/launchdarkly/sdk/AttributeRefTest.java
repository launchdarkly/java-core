package com.launchdarkly.sdk;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@SuppressWarnings("javadoc")
public class AttributeRefTest extends BaseTest {
  @Test
  public void invalidRef() {
    testInvalidRef(null, Errors.ATTR_EMPTY);
    testInvalidRef("", Errors.ATTR_EMPTY);
    testInvalidRef("/", Errors.ATTR_EMPTY);
    testInvalidRef("//", Errors.ATTR_EXTRA_SLASH);
    testInvalidRef("/a//b", Errors.ATTR_EXTRA_SLASH);
    testInvalidRef("/a/b/", Errors.ATTR_EXTRA_SLASH);
    testInvalidRef("/a~x", Errors.ATTR_INVALID_ESCAPE);
    testInvalidRef("/a/b~x", Errors.ATTR_INVALID_ESCAPE);
    testInvalidRef("/a/b~", Errors.ATTR_INVALID_ESCAPE);
  }
  
  private void testInvalidRef(String s, String expectedError) {
    AttributeRef a = AttributeRef.fromPath(s);
    assertThat(a.isValid(), is(false));
    assertThat(a.getError(), equalTo(expectedError));
    assertThat(a.toString(), equalTo(s == null ? "" : s));
    assertThat(a.getDepth(), equalTo(0));
  }
  
  @Test
  public void invalidLiteral() {
    testInvalidLiteral(null, Errors.ATTR_EMPTY);
    testInvalidLiteral("", Errors.ATTR_EMPTY);
  }
  
  private void testInvalidLiteral(String s, String expectedError) {
    AttributeRef a = AttributeRef.fromLiteral(s);
    assertThat(a.isValid(), is(false));
    assertThat(a.getError(), equalTo(expectedError));
    assertThat(a.toString(), equalTo(s == null ? "" : s));
    assertThat(a.getDepth(), equalTo(0));
  }
  
  @Test
  public void refWithNoLeadingSlash() {
    testRefWithNoLeadingSlash("name");
    testRefWithNoLeadingSlash("name/with/slashes");
    testRefWithNoLeadingSlash("name~0~1with-what-looks-like-escape-sequences");
  }
  
  private void testRefWithNoLeadingSlash(String s) {
    AttributeRef a = AttributeRef.fromPath(s);
    assertThat(a.isValid(), is(true));
    assertThat(a.getError(), nullValue());
    assertThat(a.toString(), equalTo(s));
    assertThat(a.getDepth(), equalTo(1));
    assertThat(a.getComponent(0), equalTo(s));
  }
  
  @Test
  public void refSimpleWithLeadingSlash() {
    testRefSimpleWithLeadingSlash("/name", "name");
    testRefSimpleWithLeadingSlash("/0", "0");
    testRefSimpleWithLeadingSlash("/name~1with~1slashes~0and~0tildes", "name/with/slashes~and~tildes");
  }
  
  private void testRefSimpleWithLeadingSlash(String s, String unescaped) {
    AttributeRef a = AttributeRef.fromPath(s);
    assertThat(a.isValid(), is(true));
    assertThat(a.getError(), nullValue());
    assertThat(a.toString(), equalTo(s));
    assertThat(a.getDepth(), equalTo(1));
    assertThat(a.getComponent(0), equalTo(unescaped));
  }
  
  @Test
  public void literal() {
    testLiteral("name", "name");
    testLiteral("a/b", "a/b");
    testLiteral("/a/b~c", "/~1a~1b~0c");
    testLiteral("/", "/~1");
  }
  
  private void testLiteral(String s, String escaped) {
    AttributeRef a = AttributeRef.fromLiteral(s);
    assertThat(a.isValid(), is(true));
    assertThat(a.getError(), nullValue());
    assertThat(a.toString(), equalTo(escaped));
    assertThat(a.getDepth(), equalTo(1));
    assertThat(a.getComponent(0), equalTo(s));
  }
  
  @Test
  public void getComponent() {
    testGetComponent("", 0, 0, null);
    testGetComponent("key", 1, 0, "key");
    testGetComponent("/key", 1, 0, "key");
    testGetComponent("/a/b", 2, 0, "a");
    testGetComponent("/a/b", 2, 1, "b");
    testGetComponent("/a~1b/c", 2, 0, "a/b");
    testGetComponent("/a~0b/c", 2, 0, "a~b");
    testGetComponent("/a/10/20/30x", 4, 1, "10");
    testGetComponent("/a/10/20/30x", 4, 2, "20");
    testGetComponent("/a/10/20/30x", 4, 3, "30x");
    testGetComponent("", 0, -1, null);
    testGetComponent("key", 1, -1, null);
    testGetComponent("key", 1, 1, null);
    testGetComponent("/key", 1, -1, null);
    testGetComponent("/key", 1, 1, null);
    testGetComponent("/a/b", 2, -1, null);
    testGetComponent("/a/b", 2, 2, null);
  }
  
  private void testGetComponent(String input, int depth, int index, String expectedName) {
    AttributeRef a = AttributeRef.fromPath(input);
    assertThat(a.toString(), equalTo(input));
    assertThat(a.getDepth(), equalTo(depth));
    assertThat(a.getComponent(index), equalTo(expectedName));
  }
  
  @Test
  public void equality() {
    List<List<AttributeRef>> testValues = new ArrayList<>();
    for (String s: new String[] {"", "a", "b", "/a/b", "/a/c", "///"}) {
      testValues.add(asList(AttributeRef.fromPath(s), AttributeRef.fromPath(s)));
    }
    TestHelpers.doEqualityTests(testValues);
  }
}
