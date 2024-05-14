package com.launchdarkly.sdk;

/**
 * Equivalent to {@code java.util.function.Function}, which we can't use because this package must
 * run in Android without Java 8 support.
 *
 * @param <A> input parameter type
 * @param <B> return type
 */
interface Function<A, B> {
  public B apply(A a);
}
