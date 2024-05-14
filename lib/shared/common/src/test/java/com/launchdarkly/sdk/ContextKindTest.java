package com.launchdarkly.sdk;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@SuppressWarnings("javadoc")
public class ContextKindTest {
  @Test
  public void nonEmptyValue() {
    assertThat(ContextKind.of("abc").toString(), equalTo("abc"));
  }
  
  @Test
  public void nullOrEmptyBecomesDefault() {
    assertThat(ContextKind.of(null).toString(), equalTo("user"));
    assertThat(ContextKind.of("").toString(), equalTo("user"));
  }
  
  @Test
  public void predefinedValuesAreInterned() {
    assertThat(ContextKind.of("user"), Matchers.sameInstance(ContextKind.DEFAULT));
    assertThat(ContextKind.of("multi"), Matchers.sameInstance(ContextKind.MULTI));
  }
  
  @Test
  public void isDefault() {
    assertThat(ContextKind.of("abc").isDefault(), is(false));
    assertThat(ContextKind.of("user").isDefault(), is(true));
    assertThat(ContextKind.DEFAULT.isDefault(), is(true));
  }
  
  @Test
  public void equality() {
    List<List<ContextKind>> testValues = new ArrayList<>();
    for (ContextKind kind: new ContextKind[] { ContextKind.DEFAULT, ContextKind.of("A"), ContextKind.of("a"), ContextKind.of("b") }) {
      testValues.add(asList(kind, kind));
    }
    TestHelpers.doEqualityTests(testValues);
  }
  
  @Test
  public void testHashCode() {
    assertThat(ContextKind.of("abc").hashCode(), equalTo("abc".hashCode()));
  }
}
