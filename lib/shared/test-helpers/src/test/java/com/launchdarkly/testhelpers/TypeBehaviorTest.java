package com.launchdarkly.testhelpers;

import org.junit.Test;

import java.util.Arrays;

import static com.launchdarkly.testhelpers.TypeBehavior.checkEqualsAndHashCode;
import static com.launchdarkly.testhelpers.TypeBehavior.valueFactoryFromInstances;

@SuppressWarnings("javadoc")
public class TypeBehaviorTest {
  @Test
  public void checkEqualsAndHashCodeSuccess() {
    checkEqualsAndHashCode(
        Arrays.asList(
            valueFactoryFromInstances(
                new TypeWithValueAndHashCode("a", 1),
                new TypeWithValueAndHashCode("a", 1)),            
            valueFactoryFromInstances(
                new TypeWithValueAndHashCode("b", 2),
                new TypeWithValueAndHashCode("b", 2)),            
            valueFactoryFromInstances(
                new TypeWithValueAndHashCode("c", 2),
                new TypeWithValueAndHashCode("c", 2)) // hash codes deliberately the same as b - that is allowed             
            ));
  }

  @Test(expected=AssertionError.class)
  public void checkEqualsAndHashCodeFailureForIncorrectEquality() {
    checkEqualsAndHashCode(
        Arrays.asList(
            () -> new TypeThatEqualsEveryObjectAndAlwaysHasSameHashCode(),
            () -> new TypeThatEqualsEveryObjectAndAlwaysHasSameHashCode()
            ));
  }

  @Test(expected=AssertionError.class)
  public void checkEqualsAndHashCodeFailureForEqualingNull() {
    checkEqualsAndHashCode(
        Arrays.asList(
            () -> new TypeThatEqualsEveryObjectOrNullAndAlwaysHasSameHashCode()
            ));
  }

  @Test(expected=AssertionError.class)
  public void checkEqualsAndHashCodeFailureForIncorrectInequality() {
    checkEqualsAndHashCode(
        Arrays.asList(
            () -> new TypeThatEqualsOnlyItself()
            ));
  }

  @Test(expected=AssertionError.class)
  public void checkEqualsAndHashCodeFailureForObjectNotEqualingItself() {
    checkEqualsAndHashCode(
        Arrays.asList(
            () -> new TypeThatEqualsNothing()
            ));
  }

  @Test(expected=AssertionError.class)
  public void checkEqualsAndHashCodeFailureForNonTransitiveEquality() {
    checkEqualsAndHashCode(
        Arrays.asList(
            valueFactoryFromInstances(
                new TypeThatEqualsSameOrHigherValue(1),
                new TypeThatEqualsSameOrHigherValue(2)
                )
            ));
  }

  @Test(expected=AssertionError.class)
  public void checkEqualsAndHashCodeFailureForNonTransitiveInequality() {
    checkEqualsAndHashCode(
        Arrays.asList(
            valueFactoryFromInstances(
                new TypeThatEqualsSameOrHigherValue(1),
                new TypeThatEqualsSameOrHigherValue(1)),
            valueFactoryFromInstances(
                new TypeThatEqualsSameOrHigherValue(2),
                new TypeThatEqualsSameOrHigherValue(2))
            ));
  }

  @Test(expected=AssertionError.class)
  public void checkEqualsAndHashCodeFailureForInconsistentHashCode() {
    checkEqualsAndHashCode(
        Arrays.asList(
            valueFactoryFromInstances(
                new TypeWithValueAndHashCode("a", 1),
                new TypeWithValueAndHashCode("a", 2))
            ));
  }
  
  @Test(expected=AssertionError.class)
  public void checkEqualsAndHashCodeFailureForSameInstanceSeenTwice() {
    TypeThatEqualsOnlyItself instance1 = new TypeThatEqualsOnlyItself();
    TypeThatEqualsOnlyItself instance2 = new TypeThatEqualsOnlyItself();
    checkEqualsAndHashCode(
        Arrays.asList(
            () -> instance1,
            () -> instance2
            ));
  }

  @Test
  public void checkEqualsAndHashCodeAllowsSingletonPattern() {
    TypeThatEqualsOnlyItself instance1 = new TypeThatEqualsOnlyItself();
    TypeThatEqualsOnlyItself instance2 = new TypeThatEqualsOnlyItself();
    checkEqualsAndHashCode(
        Arrays.asList(
            TypeBehavior.singletonValueFactory(instance1),
            TypeBehavior.singletonValueFactory(instance2)
            ));
  }

  private static class TypeWithValueAndHashCode {
    private final String value;
    private final int hashCode;
    
    public TypeWithValueAndHashCode(String value, int hashCode) {
      this.value = value;
      this.hashCode = hashCode;
    }
    
    public boolean equals(Object o) {
      return o instanceof TypeWithValueAndHashCode &&
          ((TypeWithValueAndHashCode)o).value.equals(this.value);
    }
    
    public int hashCode() {
      return this.hashCode;
    }
    
    public String toString() {
      return value + "/" + hashCode;
    }
  }
  
  private static class TypeThatEqualsEveryObjectAndAlwaysHasSameHashCode {
    public boolean equals(Object o) {
      return o != null;
    }
    
    public int hashCode() {
      return 1;
    }
  }

  private static class TypeThatEqualsEveryObjectOrNullAndAlwaysHasSameHashCode {
    public boolean equals(Object o) {
      return true;
    }
    
    public int hashCode() {
      return 1;
    }
  }
  
  private static class TypeThatEqualsOnlyItself {
    public boolean equals(Object o) {
      return this == o;
    }
    
    public int hashCode() {
      return 1;
    }
  }
  
  private static class TypeThatEqualsNothing {
    public boolean equals(Object o) {
      return false;
    }
    
    public int hashCode() {
      return 1;
    }
  }
  
  private static class TypeThatEqualsSameOrHigherValue {
    private final int index;
    
    TypeThatEqualsSameOrHigherValue(int index) {
      this.index = index;
    }
    
    public boolean equals(Object o) {
      return o instanceof TypeThatEqualsSameOrHigherValue &&
             ((TypeThatEqualsSameOrHigherValue)o).index >= this.index;
    }
  }
}
