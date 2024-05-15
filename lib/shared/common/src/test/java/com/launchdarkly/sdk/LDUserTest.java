package com.launchdarkly.sdk;

import com.launchdarkly.sdk.json.JsonSerialization;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.launchdarkly.sdk.Helpers.transform;
import static com.launchdarkly.sdk.TestHelpers.setFromIterable;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class LDUserTest extends BaseTest {
  private static enum OptionalStringAttributes {
    ip(
        new Function<LDUser, String>() { public String apply(LDUser u) { return u.getIp(); } },
        new BiFunction<LDUser.Builder, String, LDUser.Builder>()
          { public LDUser.Builder apply(LDUser.Builder b, String s) { return b.ip(s); } },
          new BiFunction<LDUser.Builder, String, LDUser.Builder>()
          { public LDUser.Builder apply(LDUser.Builder b, String s) { return b.privateIp(s); } }),

    firstName(
        new Function<LDUser, String>() { public String apply(LDUser u) { return u.getFirstName(); } },
        new BiFunction<LDUser.Builder, String, LDUser.Builder>()
          { public LDUser.Builder apply(LDUser.Builder b, String s) { return b.firstName(s); } },
          new BiFunction<LDUser.Builder, String, LDUser.Builder>()
          { public LDUser.Builder apply(LDUser.Builder b, String s) { return b.privateFirstName(s); } }),

    lastName(
        new Function<LDUser, String>() { public String apply(LDUser u) { return u.getLastName(); } },
        new BiFunction<LDUser.Builder, String, LDUser.Builder>()
          { public LDUser.Builder apply(LDUser.Builder b, String s) { return b.lastName(s); } },
          new BiFunction<LDUser.Builder, String, LDUser.Builder>()
          { public LDUser.Builder apply(LDUser.Builder b, String s) { return b.privateLastName(s); } }),

    email(
        new Function<LDUser, String>() { public String apply(LDUser u) { return u.getEmail(); } },
        new BiFunction<LDUser.Builder, String, LDUser.Builder>()
          { public LDUser.Builder apply(LDUser.Builder b, String s) { return b.email(s); } },
          new BiFunction<LDUser.Builder, String, LDUser.Builder>()
          { public LDUser.Builder apply(LDUser.Builder b, String s) { return b.privateEmail(s); } }),

    name(
        new Function<LDUser, String>() { public String apply(LDUser u) { return u.getName(); } },
        new BiFunction<LDUser.Builder, String, LDUser.Builder>()
          { public LDUser.Builder apply(LDUser.Builder b, String s) { return b.name(s); } },
          new BiFunction<LDUser.Builder, String, LDUser.Builder>()
          { public LDUser.Builder apply(LDUser.Builder b, String s) { return b.privateName(s); } }),

    avatar(
        new Function<LDUser, String>() { public String apply(LDUser u) { return u.getAvatar(); } },
        new BiFunction<LDUser.Builder, String, LDUser.Builder>()
          { public LDUser.Builder apply(LDUser.Builder b, String s) { return b.avatar(s); } },
          new BiFunction<LDUser.Builder, String, LDUser.Builder>()
          { public LDUser.Builder apply(LDUser.Builder b, String s) { return b.privateAvatar(s); } }),

    country(
        new Function<LDUser, String>() { public String apply(LDUser u) { return u.getCountry(); } },
        new BiFunction<LDUser.Builder, String, LDUser.Builder>()
          { public LDUser.Builder apply(LDUser.Builder b, String s) { return b.country(s); } },
          new BiFunction<LDUser.Builder, String, LDUser.Builder>()
          { public LDUser.Builder apply(LDUser.Builder b, String s) { return b.privateCountry(s); } });
    
    final UserAttribute attribute;
    final Function<LDUser, String> getter;
    final BiFunction<LDUser.Builder, String, LDUser.Builder> setter;
    final BiFunction<LDUser.Builder, String, LDUser.Builder> privateSetter;
    
    private OptionalStringAttributes(
        Function<LDUser, String> getter,
        BiFunction<LDUser.Builder, String, LDUser.Builder> setter,
        BiFunction<LDUser.Builder, String, LDUser.Builder> privateSetter
      ) {
      this.attribute = UserAttribute.forName(this.name());
      this.getter = getter;
      this.setter = setter;
      this.privateSetter = privateSetter;
    }
  };
  
  @Test
  public void simpleConstructorSetsKey() {
    LDUser user = new LDUser("key");
    assertEquals("key", user.getKey());
    assertEquals(LDValue.of("key"), user.getAttribute(UserAttribute.KEY));
    for (OptionalStringAttributes a: OptionalStringAttributes.values()) {
      assertNull(a.toString(), a.getter.apply(user));
      assertEquals(a.toString(), LDValue.ofNull(), user.getAttribute(a.attribute));
    }
    assertThat(user.isAnonymous(), is(false));
    assertThat(user.getAttribute(UserAttribute.forName("custom-attr")), equalTo(LDValue.ofNull()));
    assertThat(user.getCustomAttributes(), emptyIterable());
    assertThat(user.getPrivateAttributes(), emptyIterable());
  }
  
  @Test
  public void builderSetsOptionalStringAttribute() {
    for (OptionalStringAttributes a: OptionalStringAttributes.values()) {
      String value = "value-of-" + a.name();
      LDUser.Builder builder = new LDUser.Builder("key");
      a.setter.apply(builder, value);
      LDUser user = builder.build();
      for (OptionalStringAttributes a1: OptionalStringAttributes.values()) {
        if (a1 == a) {
          assertEquals(a.toString(), value, a1.getter.apply(user));
          assertEquals(a.toString(), LDValue.of(value), user.getAttribute(a1.attribute));
        } else {
          assertNull(a.toString(), a1.getter.apply(user));
          assertEquals(a.toString(), LDValue.ofNull(), user.getAttribute(a1.attribute)); 
        }
      }
      assertThat(user.isAnonymous(), is(false));
      assertThat(user.getAttribute(UserAttribute.forName("custom-attr")), equalTo(LDValue.ofNull()));
      assertThat(user.getCustomAttributes(), emptyIterable());
      assertThat(user.getPrivateAttributes(), emptyIterable());
      assertFalse(user.isAttributePrivate(a.attribute));
    }
  }

  @Test
  public void builderSetsPrivateOptionalStringAttribute() {
    for (OptionalStringAttributes a: OptionalStringAttributes.values()) {
      String value = "value-of-" + a.name();
      LDUser.Builder builder = new LDUser.Builder("key");
      a.privateSetter.apply(builder, value);
      LDUser user = builder.build();
      for (OptionalStringAttributes a1: OptionalStringAttributes.values()) {
        if (a1 == a) {
          assertEquals(a.toString(), value, a1.getter.apply(user));
          assertEquals(a.toString(), LDValue.of(value), user.getAttribute(a1.attribute));
        } else {
          assertNull(a.toString(), a1.getter.apply(user));
          assertEquals(a.toString(), LDValue.ofNull(), user.getAttribute(a1.attribute)); 
        }
      }
      assertThat(user.isAnonymous(), is(false));
      assertThat(user.getAttribute(UserAttribute.forName("custom-attr")), equalTo(LDValue.ofNull()));
      assertThat(user.getCustomAttributes(), emptyIterable());
      assertThat(user.getPrivateAttributes(), contains(a.attribute));
      assertTrue(user.isAttributePrivate(a.attribute));
    }
  }
  
  @Test
  public void builderSetsCustomAttributes() {
    LDValue boolValue = LDValue.of(true),
        intValue = LDValue.of(2),
        floatValue = LDValue.of(2.5),
        stringValue = LDValue.of("x"),
        jsonValue = LDValue.buildArray().build();
    LDUser user = new LDUser.Builder("key")
        .custom("custom-bool", boolValue.booleanValue())
        .custom("custom-int", intValue.intValue())
        .custom("custom-float", floatValue.floatValue())
        .custom("custom-double", floatValue.doubleValue())
        .custom("custom-string", stringValue.stringValue())
        .custom("custom-json", jsonValue)
        .build();
    List<String> names = Arrays.asList("custom-bool", "custom-int", "custom-float", "custom-double", "custom-string", "custom-json");
    assertThat(user.getAttribute(UserAttribute.forName("custom-bool")), equalTo(boolValue));
    assertThat(user.getAttribute(UserAttribute.forName("custom-int")), equalTo(intValue));
    assertThat(user.getAttribute(UserAttribute.forName("custom-float")), equalTo(floatValue));
    assertThat(user.getAttribute(UserAttribute.forName("custom-double")), equalTo(floatValue));
    assertThat(user.getAttribute(UserAttribute.forName("custom-string")), equalTo(stringValue));
    assertThat(user.getAttribute(UserAttribute.forName("custom-json")), equalTo(jsonValue));
    assertThat(setFromIterable(user.getCustomAttributes()),
        equalTo(setFromIterable(transform(names, new Function<String, UserAttribute>() {
          public UserAttribute apply(String s) { return UserAttribute.forName(s); }
        }))));
    assertThat(user.getPrivateAttributes(), emptyIterable());
    for (String name: names) {
      assertThat(name, user.isAttributePrivate(UserAttribute.forName(name)), is(false));
    }
  }

  @Test
  public void customAttributeWithNullNameIsIgnored() {
    LDUser user1 = new LDUser.Builder("key").custom(null, "1").privateCustom(null, "2").custom("a", "2").build();
    LDUser user2 = new LDUser.Builder("key").custom("a", "2").build();
    assertEquals(user2, user1);
  }
  
  @Test
  public void builderSetsPrivateCustomAttributes() {
    LDValue boolValue = LDValue.of(true),
        intValue = LDValue.of(2),
        floatValue = LDValue.of(2.5),
        stringValue = LDValue.of("x"),
        jsonValue = LDValue.buildArray().build();
    LDUser user = new LDUser.Builder("key")
        .privateCustom("custom-bool", boolValue.booleanValue())
        .privateCustom("custom-int", intValue.intValue())
        .privateCustom("custom-float", floatValue.floatValue())
        .privateCustom("custom-double", floatValue.doubleValue())
        .privateCustom("custom-string", stringValue.stringValue())
        .privateCustom("custom-json", jsonValue)
        .build();
    List<String> names = Arrays.asList("custom-bool", "custom-int", "custom-float", "custom-double", "custom-string", "custom-json");
    assertThat(user.getAttribute(UserAttribute.forName("custom-bool")), equalTo(boolValue));
    assertThat(user.getAttribute(UserAttribute.forName("custom-int")), equalTo(intValue));
    assertThat(user.getAttribute(UserAttribute.forName("custom-float")), equalTo(floatValue));
    assertThat(user.getAttribute(UserAttribute.forName("custom-double")), equalTo(floatValue));
    assertThat(user.getAttribute(UserAttribute.forName("custom-string")), equalTo(stringValue));
    assertThat(user.getAttribute(UserAttribute.forName("custom-json")), equalTo(jsonValue));
    assertThat(setFromIterable(user.getCustomAttributes()),
        equalTo(setFromIterable(transform(names, new Function<String, UserAttribute>() {
          public UserAttribute apply(String s) { return UserAttribute.forName(s); }
        }))));
    assertThat(setFromIterable(user.getPrivateAttributes()), equalTo(setFromIterable(user.getCustomAttributes())));
    for (String name: names) {
      assertThat(name, user.isAttributePrivate(UserAttribute.forName(name)), is(true));
    }
  }

  @Test
  public void canCopyUserWithBuilder() {
    LDUser user = new LDUser.Builder("key")
        .ip("127.0.0.1")
        .firstName("Bob")
        .lastName("Loblaw")
        .email("bob@example.com")
        .name("Bob Loblaw")
        .avatar("image")
        .anonymous(false)
        .country("US")
        .build();
    assertEquals(user, new LDUser.Builder(user).build());
    
    LDUser userWithPrivateAttrs = new LDUser.Builder("key").privateName("x").build();
    assertEquals(userWithPrivateAttrs, new LDUser.Builder(userWithPrivateAttrs).build());
    
    LDUser userWithCustomAttrs = new LDUser.Builder("key").custom("org", "LaunchDarkly").build();
    assertEquals(userWithCustomAttrs, new LDUser.Builder(userWithCustomAttrs).build());
  }

  @Test
  public void canSetAnonymous() {
    LDUser user1 = new LDUser.Builder("key").anonymous(true).build();
    assertThat(user1.isAnonymous(), is(true));
    assertThat(user1.getAttribute(UserAttribute.ANONYMOUS), equalTo(LDValue.of(true)));
    
    LDUser user2 = new LDUser.Builder("key").anonymous(false).build();
    assertThat(user2.isAnonymous(), is(false));
    assertThat(user2.getAttribute(UserAttribute.ANONYMOUS), equalTo(LDValue.of(false)));
  }

  @Test
  public void getAttributeGetsBuiltInAttributeEvenIfCustomAttrHasSameName() {
    LDUser user = new LDUser.Builder("key")
        .name("Jane")
        .custom("name", "Joan")
        .build();
    assertEquals(LDValue.of("Jane"), user.getAttribute(UserAttribute.forName("name")));
  }
  
  @Test
  public void equalValuesAreEqual() {
    String key = "key";
    List<List<LDUser>> testValues = new ArrayList<>();
    testValues.add(asList(new LDUser(key), new LDUser(key)));
    testValues.add(asList(new LDUser("key2"), new LDUser("key2")));
    testValues.add(asList(new LDUser.Builder(key).anonymous(true).build(),
        new LDUser.Builder(key).anonymous(true).build()));
    for (OptionalStringAttributes a: OptionalStringAttributes.values()) {
      List<LDUser> equalValues = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        LDUser.Builder builder = new LDUser.Builder(key);
        a.setter.apply(builder, "x");
        equalValues.add(builder.build());
      }
      testValues.add(equalValues);
      List<LDUser> equalValuesPrivate = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        LDUser.Builder builder = new LDUser.Builder(key);
        a.privateSetter.apply(builder, "x");
        equalValuesPrivate.add(builder.build());
      }
      testValues.add(equalValuesPrivate);
    }
    for (String attrName: new String[] { "custom1", "custom2" }) {
      LDValue[] values = new LDValue[] { LDValue.of(true), LDValue.of(false) };
      for (LDValue attrValue: values) {
        List<LDUser> equalValues = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
          LDUser.Builder builder = new LDUser.Builder(key).custom(attrName, attrValue);
          equalValues.add(builder.build());
        }
        testValues.add(equalValues);
      }
      List<LDUser> equalValues = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        LDUser.Builder builder = new LDUser.Builder(key).privateCustom(attrName, values[0]);
        equalValues.add(builder.build());
      }
      testValues.add(equalValues);
    }
    TestHelpers.doEqualityTests(testValues);
    
    assertNotEquals(null, new LDUser("userkey"));
    assertNotEquals("userkey", new LDUser("userkey"));
  }
  
  @Test
  public void simpleStringRepresentation() {
    LDUser user = new LDUser.Builder("userkey").name("x").build();
    assertEquals("LDUser(" + JsonSerialization.serialize(user) + ")", user.toString());
  }
}