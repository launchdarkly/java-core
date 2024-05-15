package com.launchdarkly.sdk;

import java.util.ArrayList;
import java.util.List;

/**
 * A mutable object that uses the builder pattern to specify properties for a multi-kind
 * {@link LDContext}.
 * <p>
 * Use this builder if you need to construct a context that has multiple {@link ContextKind}
 * values, each with its own corresponding LDContext. To define a single-kind context,
 * use {@link LDContext#builder(String)} or any of the single-kind factory methods
 * in {@link LDContext}.
 * <p>
 * Obtain an instance of ContextMultiBuilder by calling {@link LDContext#multiBuilder()};
 * then, call {@link #add(LDContext)} to specify the individual context for each kind. The
 * {@link #add(LDContext)} method returns a reference to the same builder, so calls can be
 * chained:
 * <pre><code>
 *     LDContext context = LDContext.multiBuilder()
 *       .add(LDContext.create("context-key-123abc"))
 *       .add(LDContext.create(ContextKind.of("organization"), "my-org-key"))
 *       .build();
 * </code></pre>
 * <p>
 * A ContextMultiBuilder should not be accessed by multiple threads at once. Once you have
 * called {@link #build()}, the resulting LDContext is immutable and is safe to use from
 * multiple threads. Instances created with {@link #build()} are not affected by subsequent
 * actions taken on the builder.
 * 
 * @see LDContext#createMulti(LDContext...)
 */
public final class ContextMultiBuilder {
  private List<LDContext> contexts;
  
  ContextMultiBuilder() {}
  
  /**
   * Creates an {@link LDContext} from the current builder properties.
   * <p>
   * The LDContext is immutable and will not be affected by any subsequent actions on the
   * builder.
   * <p>
   * It is possible for a ContextMultiBuilder to represent an invalid state. Instead of
   * throwing an exception, the ContextMultiBuilder always returns an LDContext, and you
   * can check {@link LDContext#isValid()} or {@link LDContext#getError()} to see if it
   * has an error. See {@link LDContext#isValid()} for more information about invalid
   * context conditions. If you pass an invalid context to an SDK method, the SDK will
   * detect this and will log a description of the error.
   * <p>
   * If only one context kind was added to the builder, this method returns a single-kind
   * LDContext rather than a multi-kind one.
   * 
   * @return a new {@link LDContext}
   */
  public LDContext build() {
    if (contexts == null || contexts.size() == 0) {
      return LDContext.failed(Errors.CONTEXT_KIND_MULTI_WITH_NO_KINDS);
    }
    if (contexts.size() == 1) {
      return contexts.get(0);
    }
    
    LDContext[] contextsArray = contexts.toArray(new LDContext[contexts.size()]);
    return LDContext.createMultiInternal(contextsArray);
  }
  
  /**
   * Adds an individual LDContext for a specific kind to the builer.
   * <p>
   * It is invalid to add more than one LDContext for the same kind, or to add an LDContext
   * that is itself invalid. This error is detected when you call {@link #build()}.
   * <p>
   * If the nested context is multi-kind, this is exactly equivalent to adding each of the
   * individual kinds from it separately. For instance, in the following example, "multi1" and
   * "multi2" end up being exactly the same:
   * <pre><code>
   *     LDContext c1 = LDContext.create(ContextKind.of("kind1"), "key1");
   *     LDContext c2 = LDContext.create(ContextKind.of("kind2"), "key2");
   *     LDContext c3 = LDContext.create(ContextKind.of("kind3"), "key3");
   *
   *     LDContext multi1 = LDContext.multiBuilder().add(c1).add(c2).add(c3).build();
   *
   *     LDContext c1plus2 = LDContext.multiBuilder().add(c1).add(c2).build();
   *     LDContext multi2 = LDContext.multiBuilder().add(c1plus2).add(c3).build();
   * </code></pre>
   *  
   * @param context the context to add
   * @return the builder
   */
  public ContextMultiBuilder add(LDContext context) {
    if (context != null) {
      if (contexts == null) {
        contexts = new ArrayList<>();
      }
      if (context.isMultiple()) {
        for (LDContext c: context.multiContexts) {
          contexts.add(c);
        }
      } else {
        contexts.add(context);
      }
    }
    return this;
  }
}
