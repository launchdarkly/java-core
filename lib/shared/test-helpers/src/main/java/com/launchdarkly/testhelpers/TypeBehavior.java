package com.launchdarkly.testhelpers;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test assertions that may be helpful in testing generic type behavior.
 *
 * @since 1.1.0
 */
public abstract class TypeBehavior {
  /**
   * A supplier interface for use {@link #checkEqualsAndHashCode(List)}.
   *
   * @param <T> the value type
   */
  public interface ValueFactory<T> {
    /**
     * Returns a new instance of the value type.
     * 
     * @return an instance
     */
    T get();
  }
  
  private static class SingletonValueFactory<T> implements ValueFactory<T> {
    private final T value;
    
    SingletonValueFactory(T value) {
      this.value = value;
    }
    
    public T get() {
      return value;
    }
  }
  
  /**
   * Creates a simple {@link ValueFactory} that returns the specified instances in order
   * each time it is called. After all instances are used, it starts over at the first.
   * This is for use with {@link #checkEqualsAndHashCode(List)}.
   * 
   * @param <T> the value type
   * @param values the instances
   * @return a value factory
   */
  @SuppressWarnings("unchecked")
  public static <T> ValueFactory<T> valueFactoryFromInstances(T...values) {
    AtomicInteger counter = new AtomicInteger(0);
    return () -> {
      int i = counter.getAndIncrement();
      if (counter.get() >= values.length) {
        counter.set(0);
      }
      return values[i];
    };
  }
  
  /**
   * Creates a simple {@link ValueFactory} that returns the sameinstance each time it
   * is called. After all instances are used, it starts over at the first. This is for use
   * with {@link #checkEqualsAndHashCode(List)}, and you should use it instead of
   * a lambda like {@code () -> value} whenever the type enforces singleton usage, because
   * otherwise {@link #checkEqualsAndHashCode(List)} will expect the return values to be
   * equal only value and <i>not</i> by reference.
   * 
   * @param <T> the value type
   * @param value the instance
   * @return a value factory
   */
  public static <T> ValueFactory<T> singletonValueFactory(T value) {
    return new SingletonValueFactory<>(value);
  }
  
  /**
   * Implements a standard test suite for custom implementations of {@code equals()} and
   * {@code hashCode()}.
   * <p>
   * The {@code valueFactories} parameter is a list of value factories. Each factory must
   * produce only instances that are equal to each other, and not equal to the instances
   * produced by any of the other factories. The test suite verifies the following:
   * <ul>
   * <li> For any instance {@code a} created by any of the factories, {@code a.equals(a)}
   * is true, {@code a.equals(null)} is false, and {@code a.equals(x)} where {@code x} is
   * an instance of a different class is false. </li> 
   * <li> For any two instances {@code a} and {@code b} created by the same factory,
   * {@code a.equals(b)}, {@code b.equals(a)}, and {@code a.hashCode() == b.hashCode()}
   * are all true. </li>
   * <li> For any two instances {@code a} and {@code b} created by different factories,
   * {@code a.equals(b)} and {@code b.equals(a)} are false (there is no requirement that
   * the hash codes are different). </li>
   * </ul>
   * <p>
   * If the type uses a singleton/interning pattern so that there can only be one
   * instance with a particular value, use {@link #singletonValueFactory(Object)} to
   * indicate that that is deliberate; otherwise {@link #checkEqualsAndHashCode(List)}
   * will assume that it is a test logic error if it sees the same instance twice. 
   *
   * @param <T> the value type
   * @param valueFactories list of factories for distinct values
   * @throws AssertionError if a test condition fails
   */
  public static <T> void checkEqualsAndHashCode(List<ValueFactory<T>> valueFactories) {
    for (int i = 0; i < valueFactories.size(); i++) {
      for (int j = 0; j < valueFactories.size(); j++) {
        T value1 = valueFactories.get(i).get();
        T value2 = valueFactories.get(j).get();
        if (i == j) {
          // Here, value1 and value2 are from the same value factory, so we expect them to be equal,
          // as follows:
          // 1. An instance must be equal to itself.
          if (!value1.equals(value1)) {
            throw new AssertionError("value was not equal to itself: " + value1);
          }
 
          // In normal usage of checkEqualsAndHashCode, we're testing for value equality (and
          // consistent hashing by value) between different instances of T that have the same
          // properties, so value1 and value2 should *not* be the exact same object. However,
          // some types use a singleton or interning pattern where it's not possible to have
          // multiple instances with the same properties; if so, the test logic should tell us
          // this by explicitly using singletonValueFactory, and then we will skip that check
          // as well as other tests that are for multiple instances (2 & 3 below).
          if (!(valueFactories.get(i) instanceof SingletonValueFactory<?>)) {
              if (value1 == value2) {
                throw new AssertionError("value factory for checkEqualsAndHashCode returned the same"
                    + " instance twice in a row; if this is intentionally a singleton, you must use"
                    + " TypeBehavior.singletonValueFactory");
              }
   
            // 2. Commutative equality: value1.equals(value2) and value2.equals(value1) must
            // both be true.
            if (!value1.equals(value2)) {
              throw new AssertionError("(" + value1 + ").equals(" + value2 + ") was false");
            }
            if (!value2.equals(value1)) {
              throw new AssertionError("(" + value1 + ").equals(" + value2 + ") was true, but (" +
                  value2 + ").equals(" + value1 + ") was false");
            }
   
            // 3. The hashCodes for two logically equal instances must be equal.
            if (value1.hashCode() != value2.hashCode()) {
              throw new AssertionError("(" + value1 + ").hashCode() was " + value1.hashCode() + " but ("
                  + value2 + ").hashCode() was " + value2.hashCode());
            }
          }
          
          // 4. An instance of anything is always unequal to null.
          if (value1.equals(null)) {
            throw new AssertionError("value was equal to null: " + value1);
          }
          // 5. An instance of T is always unequal to an instance of a class that isn't T. 
          if (value1.equals(new Object())) {
            throw new AssertionError("value was equal to Object: " + value1);
          }
        } else {
          // Here, value1 and value2 are not from the same factory, so we expect them to be
          // unequal (regardless of which one we call equals on). Note that we do *not* have a
          // similar test for the hashCodes being unequal, because that's not a requirement in
          // Java-- collisions are allowed.
          if (value1.equals(value2)) {
            throw new AssertionError("(" + value1 + ").equals(" + value2 + ") was true");
          }
          if (value2.equals(value1)) {
            throw new AssertionError("(" + value2 + ").equals(" + value1 + ") was true");
          }
        }
      }
    }
  }
}
