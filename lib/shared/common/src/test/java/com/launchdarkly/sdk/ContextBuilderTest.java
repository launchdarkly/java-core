package com.launchdarkly.sdk;

import org.junit.Test;

import java.util.List;

import static com.launchdarkly.sdk.LDContextTest.kind1;
import static com.launchdarkly.sdk.LDContextTest.kind2;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@SuppressWarnings("javadoc")
public class ContextBuilderTest {
  @Test
  public void setValueTypes() {
    assertThat(LDContext.builder("key").set("a", true).build().getValue("a"), equalTo(LDValue.of(true)));
    assertThat(LDContext.builder("key").set("a", 1).build().getValue("a"), equalTo(LDValue.of(1)));
    assertThat(LDContext.builder("key").set("a", 1.5).build().getValue("a"), equalTo(LDValue.of(1.5)));
    assertThat(LDContext.builder("key").set("a", "b").build().getValue("a"), equalTo(LDValue.of("b")));
  }
  
  @Test
  public void setValueToNullRemovesAttribute() {
    assertThat(LDContext.builder("key").set("a", true).set("a", LDValue.ofNull())
        .build().getCustomAttributeNames(), emptyIterable());
    assertThat(LDContext.builder("key").set("a", true).set("a", (LDValue)null)
        .build().getCustomAttributeNames(), emptyIterable());
  }
  
  @Test
  public void setValueCanSetBuiltInPropertiesToValidValueType() {
    assertThat(LDContext.builder(kind1, "key").set("kind", kind2.toString()).build().getKind(), equalTo(kind2));
    assertThat(LDContext.builder("key").set("key", "a").build().getKey(), equalTo("a"));
    assertThat(LDContext.builder("key").name("x").set("name", "a").build().getName(), equalTo("a"));
    assertThat(LDContext.builder("key").name("x").set("name", LDValue.ofNull()).build().getName(), nullValue());
    assertThat(LDContext.builder("key").set("anonymous", true).build().isAnonymous(), is(true));
    assertThat(LDContext.builder("key").anonymous(true).set("anonymous", false).build().isAnonymous(), is(false));
  }
  
  @Test
  public void setValueCannotSetMetaProperties() {
    LDContext c2 = LDContext.builder("key").set("privateAttributes", "x").build();
    assertThat(c2.getPrivateAttributeCount(), equalTo(0));
    assertThat(c2.getValue("privateAttributes"), equalTo(LDValue.of("x")));
  }
  
  @Test
  public void setValueIgnoresInvalidNamesAndInvalidValueTypes() {
    LDContext c = LDContext.builder("key").set("_meta",
        LDValue.buildObject().put("privateAttributes", LDValue.arrayOf(LDValue.of("a"))).build()).build();
    assertThat(c.getPrivateAttributeCount(), equalTo(0));
    assertThat(c.getValue("_meta"), equalTo(LDValue.ofNull()));

    assertThat(LDContext.builder(kind1, "key").set("kind", LDValue.of(1)).build().getKind(), equalTo(kind1));
    assertThat(LDContext.builder("key").set("key", 1).build().getKey(), equalTo("key"));
    assertThat(LDContext.builder("key").name("x").set("name", 1).build().getName(), equalTo("x"));
    assertThat(LDContext.builder("key").anonymous(true).set("anonymous", LDValue.ofNull()).build().isAnonymous(), is(true));
    assertThat(LDContext.builder("key").set("", true).build().getCustomAttributeNames(), emptyIterable());
    assertThat(LDContext.builder("key").set(null, true).build().getCustomAttributeNames(), emptyIterable());
  }

  @Test
  public void copyOnWriteAttributes() {
    ContextBuilder cb = LDContext.builder("key").set("a", 1);
    LDContext c1 = cb.build();
    
    cb.set("a", 2).set("b", 3);
    LDContext c2 = cb.build();
    
    assertThat(c1.getValue("a"), equalTo(LDValue.of(1)));
    assertThat(c1.getValue("b"), equalTo(LDValue.ofNull()));
    assertThat(c2.getValue("a"), equalTo(LDValue.of(2)));
    assertThat(c2.getValue("b"), equalTo(LDValue.of(3)));
  }

  @Test
  public void privateAttributes() {
    LDContext c1 = LDContext.create("a");
    assertThat(c1.getPrivateAttributeCount(), equalTo(0));
    assertThat(c1.getPrivateAttribute(0), nullValue());
    assertThat(c1.getPrivateAttribute(-1), nullValue());
    
    LDContext c2 = LDContext.builder("a").privateAttributes("a", "b").build();
    assertThat(c2.getPrivateAttributeCount(), equalTo(2));
    assertThat(c2.getPrivateAttribute(0), equalTo(AttributeRef.fromLiteral("a")));
    assertThat(c2.getPrivateAttribute(1), equalTo(AttributeRef.fromLiteral("b")));
    assertThat(c2.getPrivateAttribute(2), nullValue());
    assertThat(c2.getPrivateAttribute(-1), nullValue());

    LDContext c3 = LDContext.builder("a").privateAttributes(AttributeRef.fromPath("/a"),
        AttributeRef.fromPath("/a/b")).build();
    assertThat(c3.getPrivateAttributeCount(), equalTo(2));
    assertThat(c3.getPrivateAttribute(0), equalTo(AttributeRef.fromPath("/a")));
    assertThat(c3.getPrivateAttribute(1), equalTo(AttributeRef.fromPath("/a/b")));
    assertThat(c3.getPrivateAttribute(2), nullValue());
    assertThat(c3.getPrivateAttribute(-1), nullValue());
    
    // no-op cases
    assertThat(LDContext.builder("a").privateAttributes((String[])null).build()
        .getPrivateAttributeCount(), equalTo(0));
    assertThat(LDContext.builder("a").privateAttributes((AttributeRef[])null).build()
        .getPrivateAttributeCount(), equalTo(0));
  }

  @Test
  public void copyOnWritePrivateAttributes() {
    ContextBuilder cb = LDContext.builder("key").privateAttributes("a");
    LDContext c1 = cb.build();
    
    cb.privateAttributes("b");
    LDContext c2 = cb.build();
    
    assertThat(c1.getPrivateAttributeCount(), equalTo(1));
    assertThat(c2.getPrivateAttributeCount(), equalTo(2));
  }

  @Test
  public void builderFromContext() {
    List<List<LDContext>> values = LDContextTest.makeValues();
    for (List<LDContext> l: values) {
      LDContext c1 = l.get(0);
      if (c1.isMultiple() || !c1.isValid()) {
        continue;
      }
      LDContext c2 = LDContext.builderFromContext(c1).build();
      if (!c2.equals(c1)) {
        assertThat(c2, equalTo(c1));
      }
    }
  }

  @Test
  public void doesNotThrowNPEWhenReusingContext() {
    LDContext initialContext = LDContext.builder("123456").build();
    LDContext downstreamContext = LDContext.builderFromContext(initialContext)
            .set("some_attribute", "someValue")
            .build();

    assertThat(downstreamContext, notNullValue());
  }
}
