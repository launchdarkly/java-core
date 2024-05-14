package com.launchdarkly.sdk;

import org.junit.Test;

import static com.launchdarkly.sdk.LDContextTest.invalidKindThatIsLiterallyKind;
import static com.launchdarkly.sdk.LDContextTest.kind1;
import static com.launchdarkly.sdk.LDContextTest.kind2;
import static com.launchdarkly.sdk.LDContextTest.kind3;
import static com.launchdarkly.sdk.LDContextTest.shouldBeInvalid;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

@SuppressWarnings("javadoc")
public class ContextMultiBuilderTest {
  @Test
  public void builderIsEquivalentToConstructor() {
    LDContext c1 = LDContext.create(kind1, "key1");
    LDContext c2 = LDContext.create(kind2, "key2");

    assertThat(LDContext.createMulti(c1, c2),
        equalTo(LDContext.multiBuilder().add(c1).add(c2).build()));
  }
  
  @Test
  public void builderWithOneKindReturnsSingleKindContext() {
    LDContext c1 = LDContext.create("key");
    LDContext c2 = LDContext.multiBuilder().add(c1).build();
    assertThat(c2, sameInstance(c1));
  }
  
  @Test
  public void nestedMultiKindContextIsFlattened() {
    LDContext c1 = LDContext.create(kind1, "key1");
    LDContext c2 = LDContext.create(kind2, "key2");
    LDContext c3 = LDContext.create(kind3, "key3");
    LDContext c1plus2 = LDContext.multiBuilder().add(c1).add(c2).build();
    
    assertThat(LDContext.multiBuilder().add(c1plus2).add(c3).build(),
        equalTo(LDContext.multiBuilder().add(c1).add(c2).add(c3).build()));
  }
  
  @Test
  public void builderValidationErrors() {
    shouldBeInvalid(
      LDContext.multiBuilder().build(),
      Errors.CONTEXT_KIND_MULTI_WITH_NO_KINDS);

    shouldBeInvalid(
        LDContext.multiBuilder()
          .add(LDContext.create(kind1, "key1"))
          .add(LDContext.create(kind1, "key2"))
          .build(),
        Errors.CONTEXT_KIND_MULTI_DUPLICATES);
    
    shouldBeInvalid(
        LDContext.multiBuilder()
          .add(LDContext.create(""))
          .add(LDContext.create(invalidKindThatIsLiterallyKind, "key"))
          .build(),
        Errors.CONTEXT_NO_KEY + ", " + Errors.CONTEXT_KIND_CANNOT_BE_KIND);
  }
  
  @Test
  public void modifyingBuilderDoesNotAffectPreviouslyCreatedInstances() {
    LDContext c1 = LDContext.create(kind1, "key1"),
        c2 = LDContext.create(kind2, "key2"),
        c3 = LDContext.create(kind3, "key3");

    ContextMultiBuilder mb = LDContext.multiBuilder();
    mb.add(c1).add(c2);
    LDContext mc1 = mb.build();
    mb.add(c3);
    LDContext mc2 = mb.build();
    assertThat(mc1.getIndividualContextCount(), equalTo(2));
    assertThat(mc2.getIndividualContextCount(), equalTo(3));
  }
}
