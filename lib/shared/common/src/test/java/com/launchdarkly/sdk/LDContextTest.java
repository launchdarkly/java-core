package com.launchdarkly.sdk;

import com.launchdarkly.sdk.json.JsonSerialization;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public class LDContextTest {
  static final ContextKind
    kind1 = ContextKind.of("kind1"),
    kind2 = ContextKind.of("kind2"),
    kind3 = ContextKind.of("kind3"),
    invalidKindThatIsLiterallyKind = ContextKind.of("kind"),
    invalidKindWithDisallowedChar = ContextKind.of("ørg");
  
  @Test
  public void singleKindConstructors() {
    LDContext c1 = LDContext.create("x");
    assertThat(c1.getKind(), equalTo(ContextKind.DEFAULT));
    assertThat(c1.getKey(), equalTo("x"));
    assertThat(c1.getName(), nullValue());
    assertThat(c1.isAnonymous(), is(false));
    assertThat(c1.getCustomAttributeNames(), emptyIterable());
    
    LDContext c2 = LDContext.create(kind1, "x");
    assertThat(c2.getKind(), equalTo(kind1));
    assertThat(c2.getKey(), equalTo("x"));
    assertThat(c2.getName(), nullValue());
    assertThat(c2.isAnonymous(), is(false));
    assertThat(c2.getCustomAttributeNames(), emptyIterable());
  }
  
  @Test
  public void singleKindBuilderProperties() {
    assertThat(LDContext.builder(".").kind(kind1).build().getKind(), equalTo(kind1));
    assertThat(LDContext.builder(".").key("x").build().getKey(), equalTo("x"));
    assertThat(LDContext.builder(".").name("x").build().getName(), equalTo("x"));
    assertThat(LDContext.builder(".").name("x").name(null).build().getName(), nullValue());
    assertThat(LDContext.builder(".").anonymous(true).build().isAnonymous(), is(true));
    assertThat(LDContext.builder(".").anonymous(true).anonymous(false).build().isAnonymous(), is(false));
    assertThat(LDContext.builder(".").set("a", "x").build().getValue("a"), equalTo(LDValue.of("x")));
  }
  
  @Test
  public void invalidContexts() {
    shouldBeInvalid(LDContext.create(null), Errors.CONTEXT_NO_KEY);
    shouldBeInvalid(LDContext.create(""), Errors.CONTEXT_NO_KEY);
    shouldBeInvalid(LDContext.create(invalidKindThatIsLiterallyKind, "key"),
        Errors.CONTEXT_KIND_CANNOT_BE_KIND);
    shouldBeInvalid(LDContext.create(invalidKindWithDisallowedChar, "key"),
        Errors.CONTEXT_KIND_INVALID_CHARS);
    
    shouldBeInvalid(LDContext.create(ContextKind.MULTI, "key"), Errors.CONTEXT_KIND_MULTI_FOR_SINGLE);
    
    shouldBeInvalid(LDContext.createMulti(), Errors.CONTEXT_KIND_MULTI_WITH_NO_KINDS);
    
    shouldBeInvalid(
        LDContext.createMulti(
            LDContext.create(kind1, "key1"),
            LDContext.create(kind1, "key2")
            ),
        Errors.CONTEXT_KIND_MULTI_DUPLICATES);
    
    shouldBeInvalid(
        LDContext.createMulti(
            LDContext.create(""),
            LDContext.create(invalidKindThatIsLiterallyKind, "key")
            ),
        Errors.CONTEXT_NO_KEY + ", " + Errors.CONTEXT_KIND_CANNOT_BE_KIND);
  }
  
  static void shouldBeInvalid(LDContext c, String expectedError) {
    assertThat(c.isValid(), is(false));
    assertThat(c.getError(), equalTo(expectedError));
    
    // we guarantee that key is non-null even for invalid contexts, just to reduce risk of NPEs
    assertThat(c.getKey(), equalTo(""));
  }
  
  @Test
  public void multiple() {
    assertThat(LDContext.create("my-key").isMultiple(), is(false));
    assertThat(LDContext.createMulti(LDContext.create(kind1, "key1"), LDContext.create(kind2, "key2")).isMultiple(),
        is(true));
  }
  
  @Test
  public void fullyQualifiedKey() {
    assertThat(LDContext.create("abc").getFullyQualifiedKey(), equalTo("abc"));
    assertThat(LDContext.create("abc:d").getFullyQualifiedKey(), equalTo("abc:d"));
    assertThat(LDContext.create(kind1, "key1").getFullyQualifiedKey(), equalTo("kind1:key1"));
    assertThat(LDContext.create(kind1, "my:key%x/y").getFullyQualifiedKey(), equalTo("kind1:my%3Akey%25x/y"));
    assertThat(
        LDContext.createMulti(LDContext.create(kind1, "key1"), LDContext.create(kind2, "key:2")).getFullyQualifiedKey(),
        equalTo("kind1:key1:kind2:key%3A2"));
  }
  
  @Test
  public void customAttributeNames() {
    assertThat(LDContext.create("a").getCustomAttributeNames(), emptyIterable());
    
    assertThat(LDContext.builder("a").name("b").build().getCustomAttributeNames(), emptyIterable());
    
    assertThat(LDContext.builder("a").set("email", "b").set("happy", true).build().getCustomAttributeNames(),
        containsInAnyOrder("email", "happy"));
    
    // meta-attributes and non-optional attributes are not included
    assertThat(LDContext.builder("a").anonymous(true).privateAttributes("email").build().getCustomAttributeNames(),
        emptyIterable());
    
    // none for multi-kind context
    assertThat(
        LDContext.createMulti(
            LDContext.builder(kind1, "key1").set("a", "b").build(),
            LDContext.builder(kind2, "key2").set("a", "b").build()
            ).getCustomAttributeNames(),
        emptyIterable());
  }
  
  @Test
  public void getValue() {
    LDContext c = LDContext.builder("my-key").kind("org").name("x")
        .set("my-attr", "y").set("/starts-with-slash", "z").build();

    expectAttributeFoundForName(LDValue.of("org"), c, "kind");
    expectAttributeFoundForName(LDValue.of("my-key"), c, "key");
    expectAttributeFoundForName(LDValue.of("x"), c, "name");
    expectAttributeFoundForName(LDValue.of("y"), c, "my-attr");
    expectAttributeFoundForName(LDValue.of("z"), c, "/starts-with-slash");

    expectAttributeNotFoundForName(c, "/kind");
    expectAttributeNotFoundForName(c, "/key");
    expectAttributeNotFoundForName(c, "/name");
    expectAttributeNotFoundForName(c, "/my-attr");
    expectAttributeNotFoundForName(c, "other");
    expectAttributeNotFoundForName(c, "");
    expectAttributeNotFoundForName(c, "/");

    LDContext mc = LDContext.createMulti(c, LDContext.create(ContextKind.of("otherkind"), "otherkey"));

    expectAttributeFoundForName(LDValue.of("multi"), mc, "kind");

    expectAttributeNotFoundForName(mc, "/kind");
    expectAttributeNotFoundForName(mc, "key");

    // does not allow querying of subpath/element
    LDValue objValue = LDValue.buildObject().put("a", 1).build();
    LDValue arrayValue = LDValue.arrayOf(LDValue.of(1));
    LDContext c1 = LDContext.builder("key").set("obj-attr", objValue).set("array-attr", arrayValue).build();
    expectAttributeFoundForName(objValue, c1, "obj-attr");
    expectAttributeFoundForName(arrayValue, c1, "array-attr");
    expectAttributeNotFoundForName(c1, "/obj-attr/a");
    expectAttributeNotFoundForName(c1, "/array-attr/0");
  }

  private static void expectAttributeFoundForName(LDValue expectedValue, LDContext c, String name) {
      LDValue value = c.getValue(name);
      if (value.isNull()) {
        fail(String.format("attribute \"%s\" should have been found, but was not", name));
      }
      assertThat(value, equalTo(expectedValue));
  }
  
  private static void expectAttributeNotFoundForName(LDContext c, String name) {
    LDValue value = c.getValue(name);
    if (!value.isNull()) {
      fail(String.format("attribute \"%s\" should not have been found, but was", name));
    }
  }
  
  @Test
  public void getValueForRefSpecialTopLevelAttributes() {
    LDContext multi = LDContext.createMulti(
        LDContext.create("my-key"), LDContext.create(ContextKind.of("otherkind"), "otherkey"));

    expectAttributeFoundForRef(LDValue.of("org"), LDContext.create(ContextKind.of("org"), "my-key"), "kind");
    expectAttributeFoundForRef(LDValue.of("multi"), multi, "kind");

    expectAttributeFoundForRef(LDValue.of("my-key"), LDContext.create("my-key"), "key");
    expectAttributeNotFoundForRef(multi, "key");

    expectAttributeFoundForRef(LDValue.of("my-name"), LDContext.builder("key").name("my-name").build(), "name");
    expectAttributeNotFoundForRef(LDContext.create("key"), "name");
    expectAttributeNotFoundForRef(multi, "name");

    expectAttributeFoundForRef(LDValue.of(false), LDContext.create("key"), "anonymous");
    expectAttributeFoundForRef(LDValue.of(true), LDContext.builder("key").anonymous(true).build(), "anonymous");
    expectAttributeNotFoundForRef(multi, "anonymous");
  }

  private static void expectAttributeFoundForRef(LDValue expectedValue, LDContext c, String ref) {
      LDValue value = c.getValue(AttributeRef.fromPath(ref));
      if (value.isNull()) {
        fail(String.format("attribute \"{}\" should have been found, but was not", ref));
      }
      assertThat(value, equalTo(expectedValue));
  }
  
  private static void expectAttributeNotFoundForRef(LDContext c, String ref) {
    LDValue value = c.getValue(AttributeRef.fromPath(ref));
    if (!value.isNull()) {
      fail(String.format("attribute \"{}\" should not have been found, but was", ref));
    }
  }
  
  @Test
  public void getValueForRefCannotGetMetaProperties() {
    expectAttributeNotFoundForRef(LDContext.builder("key").privateAttributes("attr").build(), "privateAttributes");
  }
  
  @Test
  public void getValueForRefCustomAttributeSingleKind() {
    // simple attribute name
    expectAttributeFoundForRef(LDValue.of("abc"),
        LDContext.builder("key").set("my-attr", "abc").build(), "my-attr");

    // simple attribute name not found
    expectAttributeNotFoundForRef(LDContext.create("key"), "my-attr");
    expectAttributeNotFoundForRef(LDContext.builder("key").set("other-attr", "abc").build(), "my-attr");

    // property in object
    expectAttributeFoundForRef(LDValue.of("abc"),
        LDContext.builder("key").set("my-attr", LDValue.parse("{\"my-prop\":\"abc\"}")).build(),
        "/my-attr/my-prop");

    // property in object not found
    expectAttributeNotFoundForRef(
        LDContext.builder("key").set("my-attr", LDValue.parse("{\"my-prop\":\"abc\"}")).build(),
        "/my-attr/other-prop");

    // property in nested object
    expectAttributeFoundForRef(LDValue.of("abc"),
        LDContext.builder("key").set("my-attr", LDValue.parse("{\"my-prop\":{\"sub-prop\":\"abc\"}}")).build(),
        "/my-attr/my-prop/sub-prop");

    // property in value that is not an object
    expectAttributeNotFoundForRef(
        LDContext.builder("key").set("my-attr", "xyz").build(),
        "/my-attr/my-prop");
  }
  
  @Test
  public void getValueForInvalidRef() {
    expectAttributeNotFoundForRef(LDContext.create("key"), "/");
  }
  
  @Test
  public void multiKindContexts() {
    LDContext c1 = LDContext.create(kind1, "key1");
    LDContext c2 = LDContext.create(kind2, "key2");
    LDContext multi = LDContext.createMulti(c1, c2);

    assertThat(c1.getIndividualContextCount(), equalTo(1));
    assertThat(c1.getIndividualContext(0), sameInstance(c1));
    assertThat(c1.getIndividualContext(1), nullValue());
    assertThat(c1.getIndividualContext(-1), nullValue());
    assertThat(c1.getIndividualContext(kind1), sameInstance(c1));
    assertThat(c1.getIndividualContext(kind1.toString()), sameInstance(c1));
    assertThat(c1.getIndividualContext(kind2), nullValue());
    assertThat(c1.getIndividualContext(kind2.toString()), nullValue());

    assertThat(multi.getIndividualContextCount(), equalTo(2));
    assertThat(multi.getIndividualContext(0), sameInstance(c1));
    assertThat(multi.getIndividualContext(1), sameInstance(c2));
    assertThat(multi.getIndividualContext(2), nullValue());
    assertThat(multi.getIndividualContext(-1), nullValue());
    assertThat(multi.getIndividualContext(kind1), sameInstance(c1));
    assertThat(multi.getIndividualContext(kind1.toString()), sameInstance(c1));
    assertThat(multi.getIndividualContext(kind2), sameInstance(c2));
    assertThat(multi.getIndividualContext(kind2.toString()), sameInstance(c2));
    assertThat(multi.getIndividualContext(kind3), nullValue());
    assertThat(multi.getIndividualContext(kind3.toString()), nullValue());

    assertThat(LDContext.createMulti(c1), sameInstance(c1));

    LDContext uc1 = LDContext.create("key1");
    LDContext multi2 = LDContext.createMulti(uc1, c2);
    assertThat(multi2.getIndividualContext(ContextKind.DEFAULT), sameInstance(uc1));
    assertThat(multi2.getIndividualContext((ContextKind)null), sameInstance(uc1));
    assertThat(multi2.getIndividualContext(""), sameInstance(uc1));
    
    LDContext invalid = LDContext.create("");
    assertThat(invalid.getIndividualContextCount(), equalTo(0));
    
    LDContext c3 = LDContext.create(kind3, "key3");
    LDContext c1plus2 = LDContext.multiBuilder().add(c1).add(c2).build();
    
    assertThat(LDContext.createMulti(c1plus2, c3), equalTo(LDContext.createMulti(c1, c2, c3)));
  }

  @Test
  public void multiBuilderWithInvalidContextHasError() {
    LDContext c1 = LDContext.create(ContextKind.of("#####"), "key1");
    LDContext c2 = LDContext.create(kind2, "key2");
    LDContext output = LDContext.createMulti(c1, c2);
    assertThat(output,
            equalTo(LDContext.multiBuilder().add(c1).add(c2).build()));

    // we expect an error from the invalid context to propagate up
    assertThat(output.getError(), notNullValue());

    // we expect getting individual contexts to also fail in the error case
    assertThat(output.getIndividualContext(kind2), nullValue());
  }
  
  @Test
  public void stringRepresentation() {
    LDContext c = LDContext.create(kind1, "a");
    assertThat(c.toString(), equalTo(JsonSerialization.serialize(c)));
    
    assertThat(LDContext.create("").toString(), containsString(Errors.CONTEXT_NO_KEY));
  }
  
  @Test
  public void equality() {
    List<List<LDContext>> values = makeValues();
    TestHelpers.doEqualityTests(values);
  }
  
  static List<List<LDContext>> makeValues() {
    // This awkward pattern of creating every value twice is due to how our current
    // TestHelpers.doEqualityTests() works. When we are able to migrate to using the
    // similar method in java-test-helpers, we can use a single lambda for each instead.
    List<List<LDContext>> values = new ArrayList<>();
    
    values.add(asList(LDContext.create("a"), LDContext.create("a")));
    values.add(asList(LDContext.create("b"), LDContext.create("b")));
    
    values.add(asList(LDContext.create(kind1, "a"), LDContext.create(kind1, "a")));
    values.add(asList(LDContext.create(kind1, "b"), LDContext.create(kind1, "b")));
    
    values.add(asList(LDContext.builder("a").name("b").build(), LDContext.builder("a").name("b").build()));
   
    values.add(asList(LDContext.builder("a").anonymous(true).build(), LDContext.builder("a").anonymous(true).build()));
    
    values.add(asList(LDContext.builder("a").set("b", true).build(), LDContext.builder("a").set("b", true).build()));  
    values.add(asList(LDContext.builder("a").set("b", false).build(), LDContext.builder("a").set("b", false).build()));
    
    values.add(asList(LDContext.builder("a").set("b", true).set("c", false).build(),
        LDContext.builder("a").set("c", false).set("b", true).build())); // ordering of attributes doesn't matter
    
    values.add(asList(LDContext.builder("a").privateAttributes("b").build(),
        LDContext.builder("a").privateAttributes("b").build()));
    values.add(asList(LDContext.builder("a").privateAttributes("b", "c").build(),
        LDContext.builder("a").privateAttributes("c", "b").build())); // ordering of private attributes doesn't matter
    values.add(asList(LDContext.builder("a").privateAttributes("b", "d").build(),
        LDContext.builder("a").privateAttributes("b", "d").build()));
    
    values.add(asList(
        LDContext.createMulti(LDContext.create(kind1, "a"), LDContext.create(kind2, "b")),
        LDContext.createMulti(LDContext.create(kind2, "b"), LDContext.create(kind1, "a")) // ordering of kinds doesn't matter
        ));
    values.add(asList(
        LDContext.createMulti(LDContext.create(kind1, "a"), LDContext.create(kind2, "c")),
        LDContext.createMulti(LDContext.create(kind1, "a"), LDContext.create(kind2, "c"))
        ));
    values.add(asList(
        LDContext.createMulti(LDContext.create(kind1, "a"), LDContext.create(kind2, "b"), LDContext.create(kind3, "c")),
        LDContext.createMulti(LDContext.create(kind1, "a"), LDContext.create(kind2, "b"), LDContext.create(kind3, "c"))
        ));
    values.add(asList(LDContext.create(""), LDContext.create(""))); // invalid context
    values.add(asList(LDContext.createMulti(), LDContext.createMulti())); // invalid with a different error
    return values;
  }
  
  @Test
  public void contextFromUser() {
    LDUser u1 = new LDUser.Builder("key")
        .ip("127.0.0.1")
        .firstName("Bob")
        .lastName("Loblaw")
        .email("bob@example.com")
        .privateName("Bob Loblaw")
        .avatar("image")
        .country("US")
        .anonymous(true)
        .build();
    LDContext c1 = LDContext.fromUser(u1);
    assertThat(c1, equalTo(
        LDContext.builder(u1.getKey())
          .set("ip", u1.getIp())
          .set("firstName", u1.getFirstName())
          .set("lastName", u1.getLastName())
          .set("email", u1.getEmail())
          .set("name", u1.getName())
          .set("avatar", u1.getAvatar())
          .set("country", u1.getCountry())
          .privateAttributes("name")
          .anonymous(true)
          .build()
        ));
    
    // test case where there were no built-in optional attrs, only custom
    LDUser u2 = new LDUser.Builder("key")
        .custom("c1", "v1")
        .privateCustom("c2", "v2")
        .build();
    LDContext c2 = LDContext.fromUser(u2);
    assertThat(c2, equalTo(
        LDContext.builder(u2.getKey())
          .set("c1", "v1")
          .set("c2", "v2")
          .privateAttributes("c2")
          .build()
        ));
    
    // anonymous user with null key
    LDUser u3 = new LDUser.Builder((String)null).anonymous(true).build();
    LDContext c3 = LDContext.fromUser(u3);
    assertThat(c3.isValid(), is(true));
    assertThat(c3.getKey(), equalTo(""));
    assertThat(c3.isAnonymous(), is(true));
  }
  
  @Test
  public void contextFromUserErrors() {
    LDContext c1 = LDContext.fromUser(null);
    assertThat(c1.isValid(), is(false));
    assertThat(c1.getError(), equalTo(Errors.CONTEXT_FROM_NULL_USER));

    LDContext c2 = LDContext.fromUser(new LDUser((String)null));
    assertThat(c2.isValid(), is(false));
    assertThat(c2.getError(), equalTo(Errors.CONTEXT_NO_KEY));
}
}
