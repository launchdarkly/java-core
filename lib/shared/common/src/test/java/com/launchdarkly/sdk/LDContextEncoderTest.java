package com.launchdarkly.sdk;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

@SuppressWarnings("javadoc")
public class LDContextEncoderTest extends BaseTest {
  @Test
  public void nullContextReturnsEmptyMap() {
    Map<String, Object> result = LDContextEncoder.encode(null);
    assertThat(result.isEmpty(), is(true));
  }

  @Test
  public void invalidContextReturnsEmptyMap() {
    LDContext invalid = LDContext.create("");
    assertThat(invalid.isValid(), is(false));
    assertThat(LDContextEncoder.encode(invalid).isEmpty(), is(true));
  }

  @Test
  public void singleKindContextIncludesKindKeyAndAnonymous() {
    LDContext context = LDContext.builder("user-key").build();
    Map<String, Object> result = LDContextEncoder.encode(context);
    assertThat(result.get("kind"), is((Object) "user"));
    assertThat(result.get("key"), is((Object) "user-key"));
    assertThat(result.containsKey("anonymous"), is(true));
    assertThat(result.get("anonymous"), is((Object) Boolean.FALSE));
  }

  @Test
  public void singleKindContextIncludesNameWhenPresent() {
    LDContext context = LDContext.builder("user-key").name("Bob").build();
    Map<String, Object> result = LDContextEncoder.encode(context);
    assertThat(result.get("name"), is((Object) "Bob"));
  }

  @Test
  public void singleKindContextOmitsNameWhenNull() {
    LDContext context = LDContext.builder("user-key").build();
    Map<String, Object> result = LDContextEncoder.encode(context);
    assertThat(result.containsKey("name"), is(false));
  }

  @Test
  public void anonymousAlwaysPresentEvenWhenFalse() {
    LDContext context = LDContext.builder("key").anonymous(false).build();
    Map<String, Object> result = LDContextEncoder.encode(context);
    assertThat(result.containsKey("anonymous"), is(true));
    assertThat(result.get("anonymous"), is((Object) Boolean.FALSE));
  }

  @Test
  public void anonymousTrueIsPresentWhenSet() {
    LDContext context = LDContext.builder("key").anonymous(true).build();
    Map<String, Object> result = LDContextEncoder.encode(context);
    assertThat(result.get("anonymous"), is((Object) Boolean.TRUE));
  }

  @Test
  public void singleKindContextIncludesCustomAttributes() {
    LDContext context = LDContext.builder("user-key")
        .set("tier", "gold")
        .set("score", 42)
        .build();
    Map<String, Object> result = LDContextEncoder.encode(context);
    assertThat(result.get("tier"), is((Object) "gold"));
    assertThat(result.get("score"), is((Object) 42L));
  }

  @Test
  public void customAttributeObjectValueIsDecoded() {
    LDContext context = LDContext.builder("user-key")
        .set("address", LDValue.buildObject().put("city", "Oakland").build())
        .build();
    Map<String, Object> result = LDContextEncoder.encode(context);
    @SuppressWarnings("unchecked")
    Map<String, Object> address = (Map<String, Object>) result.get("address");
    assertThat(address, is(not(nullValue())));
    assertThat(address.get("city"), is((Object) "Oakland"));
  }

  @Test
  public void customAttributeArrayValueIsDecoded() {
    LDContext context = LDContext.builder("user-key")
        .set("tags", LDValue.buildArray().add("a").add("b").build())
        .build();
    Map<String, Object> result = LDContextEncoder.encode(context);
    @SuppressWarnings("unchecked")
    List<Object> tags = (List<Object>) result.get("tags");
    assertThat(tags, is(not(nullValue())));
    assertThat(tags.get(0), is((Object) "a"));
    assertThat(tags.get(1), is((Object) "b"));
  }

  @Test
  public void multiKindContextHasKindMultiAndFullyQualifiedKey() {
    LDContext multi = LDContext.createMulti(
        LDContext.builder("user-key").build(),
        LDContext.builder(ContextKind.of("org"), "org-key").build());
    Map<String, Object> result = LDContextEncoder.encode(multi);
    assertThat(result.get("kind"), is((Object) "multi"));
    assertThat(result.get("key"), is((Object) multi.getFullyQualifiedKey()));
  }

  @Test
  public void multiKindContextContainsPerKindObjects() {
    LDContext multi = LDContext.createMulti(
        LDContext.builder("user-key").name("Bob").build(),
        LDContext.builder(ContextKind.of("org"), "org-key").set("tier", "gold").build());
    Map<String, Object> result = LDContextEncoder.encode(multi);

    @SuppressWarnings("unchecked")
    Map<String, Object> userMap = (Map<String, Object>) result.get("user");
    assertThat(userMap, is(not(nullValue())));
    assertThat(userMap.get("key"), is((Object) "user-key"));
    assertThat(userMap.get("name"), is((Object) "Bob"));

    @SuppressWarnings("unchecked")
    Map<String, Object> orgMap = (Map<String, Object>) result.get("org");
    assertThat(orgMap, is(not(nullValue())));
    assertThat(orgMap.get("key"), is((Object) "org-key"));
    assertThat(orgMap.get("tier"), is((Object) "gold"));
  }

  @Test
  public void multiKindPerKindObjectsOmitKind() {
    LDContext multi = LDContext.createMulti(
        LDContext.builder("user-key").build(),
        LDContext.builder(ContextKind.of("org"), "org-key").build());
    Map<String, Object> result = LDContextEncoder.encode(multi);

    @SuppressWarnings("unchecked")
    Map<String, Object> userMap = (Map<String, Object>) result.get("user");
    assertThat(userMap.containsKey("kind"), is(false));

    @SuppressWarnings("unchecked")
    Map<String, Object> orgMap = (Map<String, Object>) result.get("org");
    assertThat(orgMap.containsKey("kind"), is(false));
  }

  @Test
  public void multiKindNestedContextsIncludeAnonymous() {
    LDContext multi = LDContext.createMulti(
        LDContext.builder("user-key").build(),
        LDContext.builder(ContextKind.of("org"), "org-key").anonymous(true).build());
    Map<String, Object> result = LDContextEncoder.encode(multi);

    @SuppressWarnings("unchecked")
    Map<String, Object> userMap = (Map<String, Object>) result.get("user");
    assertThat(userMap.containsKey("anonymous"), is(true));
    assertThat(userMap.get("anonymous"), is((Object) Boolean.FALSE));

    @SuppressWarnings("unchecked")
    Map<String, Object> orgMap = (Map<String, Object>) result.get("org");
    assertThat(orgMap.get("anonymous"), is((Object) Boolean.TRUE));
  }

  @Test
  public void multiKindNestedContextIncludesNameOnlyWhenPresent() {
    LDContext multi = LDContext.createMulti(
        LDContext.builder("user-key").name("Alice").build(),
        LDContext.builder(ContextKind.of("device"), "device-key").build());
    Map<String, Object> result = LDContextEncoder.encode(multi);

    @SuppressWarnings("unchecked")
    Map<String, Object> userMap = (Map<String, Object>) result.get("user");
    assertThat(userMap.get("name"), is((Object) "Alice"));

    @SuppressWarnings("unchecked")
    Map<String, Object> deviceMap = (Map<String, Object>) result.get("device");
    assertThat(deviceMap.containsKey("name"), is(false));
  }

  @Test
  public void customKindContextIsEncoded() {
    LDContext context = LDContext.builder(ContextKind.of("device"), "device-123").build();
    Map<String, Object> result = LDContextEncoder.encode(context);
    assertThat(result.get("kind"), is((Object) "device"));
    assertThat(result.get("key"), is((Object) "device-123"));
  }

  @Test
  public void multiKindWithKeyKindContextPreservesFQK() {
    // "key"-kind member is last in the loop — FQK write after loop must still win.
    LDContext multi = LDContext.createMulti(
        LDContext.builder("user-key").build(),
        LDContext.builder(ContextKind.of("key"), "key-key").build());
    Map<String, Object> result = LDContextEncoder.encode(multi);
    assertThat(result.get("key"), instanceOf(String.class));
    assertThat(result.get("key"), is((Object) multi.getFullyQualifiedKey()));
  }

  @Test
  public void multiKindWithKeyKindFirstPreservesFQK() {
    // "key"-kind member is first in the loop — ordering must not affect the outcome.
    LDContext multi = LDContext.createMulti(
        LDContext.builder(ContextKind.of("key"), "key-key").build(),
        LDContext.builder("user-key").build());
    Map<String, Object> result = LDContextEncoder.encode(multi);
    assertThat(result.get("key"), instanceOf(String.class));
    assertThat(result.get("key"), is((Object) multi.getFullyQualifiedKey()));
  }

  @Test
  public void multiKindWithKeyKindMemberDataIsOverwrittenByFQK() {
    // Documents the known trade-off: the "key"-kind nested context is not accessible in the
    // output because its map entry is overwritten by the FQK string.
    LDContext multi = LDContext.createMulti(
        LDContext.builder("user-key").build(),
        LDContext.builder(ContextKind.of("key"), "key-key").name("ShouldNotAppear").build());
    Map<String, Object> result = LDContextEncoder.encode(multi);
    assertThat(result.get("key"), instanceOf(String.class));
  }

  @Test
  public void nestedObjectCustomAttributeIsDeeplyTraversable() {
    LDContext context = LDContext.builder("user-key")
        .set("meta", LDValue.buildObject()
            .put("level1", LDValue.buildObject().put("level2", "deep-value").build())
            .build())
        .build();
    Map<String, Object> result = LDContextEncoder.encode(context);
    @SuppressWarnings("unchecked")
    Map<String, Object> meta = (Map<String, Object>) result.get("meta");
    @SuppressWarnings("unchecked")
    Map<String, Object> level1 = (Map<String, Object>) meta.get("level1");
    assertThat(level1.get("level2"), is((Object) "deep-value"));
  }
}
