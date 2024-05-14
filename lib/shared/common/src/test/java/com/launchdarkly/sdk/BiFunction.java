package com.launchdarkly.sdk;

/**
 * Equivalent to {@code java.util.function.BiFunction}, which we can't use because this package must
 * run in Android, where types from Java 8+ are not available.
 *
 * @param <A> input parameter type
 * @param <B> second input parameter type
 * @param <C> return type
 */
interface BiFunction<A, B, C> {
  public C apply(A a, B b);
}
