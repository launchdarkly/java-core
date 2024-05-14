package com.launchdarkly.sdk;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonWriter;
import com.launchdarkly.sdk.json.JsonSerializable;
import com.launchdarkly.sdk.json.JsonSerialization;
import com.launchdarkly.sdk.json.SerializationException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import static com.launchdarkly.sdk.Helpers.transform;
import static java.util.Collections.emptyList;

/**
 * An immutable instance of any data type that is allowed in JSON.
 * <p>
 * An {@link LDValue} instance can be a null (that is, an instance that represents a JSON null value,
 * rather than a Java null reference), a boolean, a number (always encoded internally as double-precision
 * floating-point, but can be treated as an integer), a string, an ordered list of {@link LDValue}
 * values (a JSON array), or a map of strings to {@link LDValue} values (a JSON object). It is easily
 * convertible to standard Java types.
 * <p>
 * This can be used to represent complex data in a context attribute (see {@link ContextBuilder#set(String, LDValue)}),
 * or to get a feature flag value that uses a complex type or that does not always use the same
 * type (see the client's {@code jsonValueVariation} methods).
 * <p>
 * While the LaunchDarkly SDK uses Gson internally for JSON parsing, it uses {@link LDValue} rather
 * than Gson's {@code JsonElement} type for two reasons. First, this allows Gson types to be excluded
 * from the API, so the SDK does not expose this dependency and cannot cause version conflicts in
 * applications that use Gson themselves. Second, Gson's array and object types are mutable, which can
 * cause concurrency risks.
 * <p>
 * {@link LDValue} can be converted to and from JSON in any of these ways:
 * <ol>
 * <li> With the {@link LDValue} methods {@link #toJsonString()} and {@link #parse(String)}.
 * <li> With {@link com.launchdarkly.sdk.json.JsonSerialization}.
 * <li> With Gson, if and only if you configure your {@code Gson} instance with
 * {@link com.launchdarkly.sdk.json.LDGson}.
 * <li> With Jackson, if and only if you configure your {@code ObjectMapper} instance with
 * {@link com.launchdarkly.sdk.json.LDJackson}.
 * </ol>
 */
@JsonAdapter(LDValueTypeAdapter.class)
public abstract class LDValue implements JsonSerializable {
  /**
   * Returns the same value if non-null, or {@link #ofNull()} if null.
   * 
   * @param value an {@link LDValue} or null
   * @return an {@link LDValue} which will never be a null reference
   */
  public static LDValue normalize(LDValue value) {
    return value == null ? ofNull() : value;
  }
  
  /**
   * Returns an instance for a null value. The same instance is always used.
   * 
   * @return an LDValue containing null
   */
  public static LDValue ofNull() {
    return LDValueNull.INSTANCE;
  }
  
  /**
   * Returns an instance for a boolean value. The same instances for {@code true} and {@code false}
   * are always used.
   * 
   * @param value a boolean value
   * @return an LDValue containing that value
   */
  public static LDValue of(boolean value) {
    return LDValueBool.fromBoolean(value);
  }
  
  /**
   * Returns an instance for a numeric value.
   * 
   * @param value an integer numeric value
   * @return an LDValue containing that value
   */
  public static LDValue of(int value) {
    return LDValueNumber.fromDouble(value);
  }

  /**
   * Returns an instance for a numeric value.
   * <p>
   * Note that the LaunchDarkly service, and most of the SDKs, represent numeric values internally
   * in 64-bit floating-point, which has slightly less precision than a signed 64-bit {@code long};
   * therefore, the full range of {@code long} values cannot be accurately represented. If you need
   * to set a context attribute to a numeric value with more significant digits than will fit in a
   * {@code double}, it is best to encode it as a string.
   * 
   * @param value a long integer numeric value
   * @return an LDValue containing that value
   */
  public static LDValue of(long value) {
    return LDValueNumber.fromDouble(value);
  }
  
  /**
   * Returns an instance for a numeric value.
   * 
   * @param value a floating-point numeric value
   * @return an LDValue containing that value
   */
  public static LDValue of(float value) {
    return LDValueNumber.fromDouble(value);
  }
  
  /**
   * Returns an instance for a numeric value.
   * 
   * @param value a floating-point numeric value
   * @return an LDValue containing that value
   */
  public static LDValue of(double value) {
    return LDValueNumber.fromDouble(value);
  }
  
  /**
   * Returns an instance for a string value (or a null).
   * 
   * @param value a nullable String reference
   * @return an LDValue containing a string, or {@link #ofNull()} if the value was null.
   */
  public static LDValue of(String value) {
    return value == null ? ofNull() : LDValueString.fromString(value);
  }

  /**
   * Starts building an array value. The elements can be of any type supported by LDValue.
   * <pre><code>
   *     LDValue arrayOfInts = LDValue.buildArray().add(2).add("three").build():
   * </code></pre>
   * If the values are all of the same type, you may also use {@link LDValue.Converter#arrayFrom(Iterable)}
   * or {@link LDValue.Converter#arrayOf(Object...)}.
   * 
   * @return an {@link ArrayBuilder}
   */
  public static ArrayBuilder buildArray() {
    return new ArrayBuilder();
  }

  /**
   * Creates an array value from the specified values. The elements can be of any type supported by LDValue.
   * <pre><code>
   *     LDValue arrayOfMixedValues = LDValue.arrayOf(LDValue.of(2), LDValue.of("three"));
   * </code></pre>
   * If the values are all of the same type, you may also use {@link LDValue.Converter#arrayFrom(Iterable)}
   * or {@link LDValue.Converter#arrayOf(Object...)}.
   * 
   * @param values any number of values
   * @return an immutable array value
   */
  public static LDValue arrayOf(LDValue... values) {
    return LDValueArray.fromList(values == null ? null : Arrays.asList(values));
  }
  
  /**
   * Starts building an object value.
   * <pre><code>
   *     LDValue objectVal = LDValue.buildObject().put("key", LDValue.int(1)).build():
   * </code></pre>
   * If the values are all of the same type, you may also use {@link LDValue.Converter#objectFrom(Map)}.
   * 
   * @return an {@link ObjectBuilder}
   */
  public static ObjectBuilder buildObject() {
    return new ObjectBuilder();
  }
  
  /**
   * Parses an LDValue from a JSON representation.
   * <p>
   * This convenience method is equivalent to using {@link JsonSerialization#deserialize(String, Class)}
   * with the {@code LDValue} class, except for two things:
   * <p>
   * 1. You do not have to provide the class parameter.
   * <p>
   * 2. Parsing errors are thrown as an unchecked {@code RuntimeException} that wraps the checked
   * {@link SerializationException}, making this method somewhat more convenient in cases such as
   * test code where explicit error handling is less important.
   * 
   * @param json a JSON string
   * @return an LDValue
   */
  public static LDValue parse(String json) {
    try {
      return LDValue.normalize(JsonSerialization.deserialize(json, LDValue.class));
    } catch (SerializationException e) {
      throw new RuntimeException(e);
    }
  }
  
  /**
   * Gets the JSON type for this value.
   * 
   * @return the appropriate {@link LDValueType}
   */
  public abstract LDValueType getType();
  
  /**
   * Tests whether this value is a null.
   * 
   * @return {@code true} if this is a null value
   */
  public boolean isNull() {
    return false;
  }

  /**
   * Returns this value as a boolean if it is explicitly a boolean. Otherwise returns {@code false}.
   * 
   * @return a boolean
   */
  public boolean booleanValue() {
    return false;
  }
  
  /**
   * Tests whether this value is a number (not a numeric string).
   * 
   * @return {@code true} if this is a numeric value
   */
  public boolean isNumber() {
    return false;
  }
  
  /**
   * Tests whether this value is a number that is also an integer.
   * <p>
   * JSON does not have separate types for integer and floating-point values; they are both just
   * numbers. This method returns true if and only if the actual numeric value has no fractional
   * component, so {@code LDValue.of(2).isInt()} and {@code LDValue.of(2.0f).isInt()} are both true.
   * 
   * @return {@code true} if this is an integer value
   */
  public boolean isInt() {
    return false;
  }
  
  /**
   * Returns this value as an {@code int} if it is numeric. Returns zero for all non-numeric values.
   * <p>
   * If the value is a number but not an integer, it will be rounded toward zero (truncated).
   * This is consistent with Java casting behavior, and with most other LaunchDarkly SDKs.
   * 
   * @return an {@code int} value
   */
  public int intValue() {
    return 0;
  }

  /**
   * Returns this value as a {@code long} if it is numeric. Returns zero for all non-numeric values.
   * <p>
   * If the value is a number but not an integer, it will be rounded toward zero (truncated).
   * This is consistent with Java casting behavior, and with most other LaunchDarkly SDKs.
   * 
   * @return a {@code long} value
   */
  public long longValue() {
    return 0;
  }
  
  /**
   * Returns this value as a {@code float} if it is numeric. Returns zero for all non-numeric values.
   * 
   * @return a {@code float} value
   */
  public float floatValue() {
    return 0;
  }

  /**
   * Returns this value as a {@code double} if it is numeric. Returns zero for all non-numeric values.
   * 
   * @return a {@code double} value
   */
  public double doubleValue() {
    return 0;
  }

  /**
   * Tests whether this value is a string.
   * 
   * @return {@code true} if this is a string value
   */
  public boolean isString() {
    return false;
  }

  /**
   * Returns this value as a {@code String} if it is a string. Returns {@code null} for all non-string values.
   * 
   * @return a nullable string value
   */
  public String stringValue() {
    return null;
  }
  
  /**
   * Returns the number of elements in an array or object. Returns zero for all other types.
   * 
   * @return the number of array elements or object properties
   */
  public int size() {
    return 0;
  }

  /**
   * Enumerates the property names in an object. Returns an empty iterable for all other types.
   * 
   * @return the property names
   */
  public Iterable<String> keys() {
    return emptyList();
  }
  
  /**
   * Enumerates the values in an array or object. Returns an empty iterable for all other types.
   * 
   * @return an iterable of {@link LDValue} values
   */
  public Iterable<LDValue> values() {
    return emptyList();
  }
  
  /**
   * Enumerates the values in an array or object, converting them to a specific type. Returns an empty
   * iterable for all other types.
   * <p>
   * This is an efficient method because it does not copy values to a new list, but returns a view
   * into the existing array.
   * <p>
   * Example:
   * <pre><code>
   *     LDValue anArrayOfInts = LDValue.Convert.Integer.arrayOf(1, 2, 3);
   *     for (int i: anArrayOfInts.valuesAs(LDValue.Convert.Integer)) { println(i); }
   * </code></pre>
   * <p>
   * For boolean and numeric types, even though the corresponding Java type is a nullable class like
   * {@code Boolean} or {@code Integer}, {@code valuesAs} will never return a null element; instead,
   * it will use the appropriate default value for the primitive type (false or zero).
   * 
   * @param <T> the desired type
   * @param converter the {@link Converter} for the specified type
   * @return an iterable of values of the specified type
   */
  public <T> Iterable<T> valuesAs(final Converter<T> converter) {
    return transform(values(), new Function<LDValue, T>() {
      @Override
      public T apply(LDValue a) {
        return converter.toType(a);
      }
    });
  }
  
  /**
   * Returns an array element by index. Returns {@link #ofNull()} if this is not an array or if the
   * index is out of range (will never throw an exception).
   * 
   * @param index the array index
   * @return the element value or {@link #ofNull()}
   */
  public LDValue get(int index) {
    return ofNull();
  }
  
  /**
   * Returns an object property by name. Returns {@link #ofNull()} if this is not an object or if the
   * key is not found (will never throw an exception).
   * 
   * @param name the property name
   * @return the property value or {@link #ofNull()}
   */
  public LDValue get(String name) {
    return ofNull();
  }
  
  /**
   * Converts this value to its JSON serialization.
   * <p>
   * This method is equivalent to passing the {@code LDValue} instance to
   * {@link JsonSerialization#serialize(JsonSerializable)}.
   * 
   * @return a JSON string
   */
  public String toJsonString() {
    return JsonSerialization.serialize(this);
  }
  
  abstract void write(JsonWriter writer) throws IOException;
  
  static boolean isInteger(double value) {
    return value == (double)((int)value);
  }
  
  /**
   * Returns a string representation of this value.
   * <p>
   * This method currently returns the same JSON serialization as {@link #toJsonString()}. However,
   * like most {@code toString()} implementations, it is intended mainly for convenience in
   * debugging or other use cases where the goal is simply to have a human-readable format; it is
   * not guaranteed to always match {@link #toJsonString()} in the future. If you need to verify
   * the value type or other properties programmatically, use the getter methods of {@code LDValue}. 
   */
  @Override
  public String toString() {
    return toJsonString();
  }
  
  /**
   * Returns true if the other object is an {@link LDValue} that is logically equal.
   * <p>
   * This is a deep equality comparison: for JSON arrays each element is compared recursively, and
   * for JSON objects all property names and values must be deeply equal regardless of ordering.
   */
  @Override
  public boolean equals(Object o) {
    if (o instanceof LDValue) {
      if (o == this) {
        return true;
      }
      LDValue other = (LDValue)o;
      if (getType() == other.getType()) {
        switch (getType()) {
        case NULL: return other.isNull(); // COVERAGE: won't hit this case because ofNull() is a singleton, so (o == this) will be true
        case NUMBER: return doubleValue() == other.doubleValue();
        case BOOLEAN: return false; // boolean true and false are singletons, so if o != this, they're unequal
        case STRING: return stringValue().equals(other.stringValue());
        case ARRAY:
          if (size() != other.size()) {
            return false;
          }
          for (int i = 0; i < size(); i++) {
            if (!get(i).equals(other.get(i))) {
              return false;
            }
          }
          return true; 
        case OBJECT:
          if (size() != other.size()) {
            return false;
          }
          for (String name: keys()) {
            if (!get(name).equals(other.get(name))) {
              return false;
            }
          }
          return true;
        default:
          break;
        }
      }
    }
    return false;
  }
  
  @Override
  public int hashCode() {
    switch (getType()) {
    case BOOLEAN: return booleanValue() ? 1 : 0;
    case NUMBER: return intValue();
    case STRING: return stringValue().hashCode();
    case ARRAY:
      int ah = 0;
      for (LDValue v: values()) {
        ah = ah * 31 + v.hashCode();
      }
      return ah;
    case OBJECT:
      int oh = 0;
      for (String name: keys()) {
        oh = (oh * 31 + name.hashCode()) * 31 + get(name).hashCode();
      }
      return oh;
    default:
      return 0;
    }
  }
  
  /**
   * Defines a conversion between {@link LDValue} and some other type.
   * <p>
   * Besides converting individual values, this provides factory methods like {@link #arrayOf}
   * which transform a collection of the specified type to the corresponding {@link LDValue}
   * complex type.
   * 
   * @param <T> the type to convert from/to
   */
  public static abstract class Converter<T> {
    /**
     * Converts a value of the specified type to an {@link LDValue}.
     * <p>
     * This method should never throw an exception; if for some reason the value is invalid,
     * it should return {@link LDValue#ofNull()}.
     * 
     * @param value a value of this type
     * @return an {@link LDValue}
     */
    public abstract LDValue fromType(T value);
    
    /**
     * Converts an {@link LDValue} to a value of the specified type.
     * <p>
     * This method should never throw an exception; if the conversion cannot be done, it should
     * return the default value of the given type (zero for numbers, null for nullable types).
     * 
     * @param value an {@link LDValue}
     * @return a value of this type
     */
    public abstract T toType(LDValue value);
    
    /**
     * Initializes an {@link LDValue} as an array, from a sequence of this type.
     * <p>
     * Values are copied, so subsequent changes to the source values do not affect the array.
     * <p>
     * Example:
     * <pre><code>
     *     List&lt;Integer&gt; listOfInts = ImmutableList.&lt;Integer&gt;builder().add(1).add(2).add(3).build();
     *     LDValue arrayValue = LDValue.Convert.Integer.arrayFrom(listOfInts);
     * </code></pre>
     * 
     * @param values a sequence of elements of the specified type
     * @return a value representing a JSON array, or {@link LDValue#ofNull()} if the parameter was null
     * @see LDValue#buildArray()
     */
    public LDValue arrayFrom(Iterable<T> values) {
      ArrayBuilder ab = LDValue.buildArray();
      for (T value: values) {
        ab.add(fromType(value));
      }
      return ab.build();
    }

    /**
     * Initializes an {@link LDValue} as an array, from a sequence of this type.
     * <p>
     * Values are copied, so subsequent changes to the source values do not affect the array.
     * <p>
     * Example:
     * <pre><code>
     *     LDValue arrayValue = LDValue.Convert.Integer.arrayOf(1, 2, 3);
     * </code></pre>
     * 
     * @param values a sequence of elements of the specified type
     * @return a value representing a JSON array, or {@link LDValue#ofNull()} if the parameter was null
     * @see LDValue#buildArray()
     */
    @SuppressWarnings("unchecked")
    public LDValue arrayOf(T... values) {
      ArrayBuilder ab = LDValue.buildArray();
      for (T value: values) {
        ab.add(fromType(value));
      }
      return ab.build();
    }
    
    /**
     * Initializes an {@link LDValue} as an object, from a map containing this type.
     * <p>
     * Values are copied, so subsequent changes to the source map do not affect the array.
     * <p>
     * Example:
     * <pre><code>
     *     Map&lt;String, Integer&gt; mapOfInts = ImmutableMap.&lt;String, Integer&gt;builder().put("a", 1).build();
     *     LDValue objectValue = LDValue.Convert.Integer.objectFrom(mapOfInts);
     * </code></pre>
     * 
     * @param map a map with string keys and values of the specified type
     * @return a value representing a JSON object, or {@link LDValue#ofNull()} if the parameter was null
     * @see LDValue#buildObject()
     */
    public LDValue objectFrom(Map<String, T> map) {
      ObjectBuilder ob = LDValue.buildObject();
      for (String key: map.keySet()) {
        ob.put(key, fromType(map.get(key)));
      }
      return ob.build();
    }
  }
  
  /**
   * Predefined instances of {@link LDValue.Converter} for commonly used types.
   * <p>
   * These are mostly useful for methods that convert {@link LDValue} to or from a collection of
   * some type, such as {@link LDValue.Converter#arrayOf(Object...)} and
   * {@link LDValue#valuesAs(Converter)}.
   */
  public static abstract class Convert {
    private Convert() {}
    
    /**
     * A {@link LDValue.Converter} for booleans.
     */
    public static final Converter<java.lang.Boolean> Boolean = new Converter<java.lang.Boolean>() {
      public LDValue fromType(java.lang.Boolean value) {
        return value == null ? LDValue.ofNull() : LDValue.of(value.booleanValue());
      }
      public java.lang.Boolean toType(LDValue value) {
        return java.lang.Boolean.valueOf(value.booleanValue());
      }
    };
    
    /**
     * A {@link LDValue.Converter} for integers.
     */
    public static final Converter<java.lang.Integer> Integer = new Converter<java.lang.Integer>() {
      public LDValue fromType(java.lang.Integer value) {
        return value == null ? LDValue.ofNull() : LDValue.of(value.intValue());
      }
      public java.lang.Integer toType(LDValue value) {
        return java.lang.Integer.valueOf(value.intValue());
      }
    };

    /**
     * A {@link LDValue.Converter} for long integers.
     * <p>
     * Note that the LaunchDarkly service, and most of the SDKs, represent numeric values internally
     * in 64-bit floating-point, which has slightly less precision than a signed 64-bit {@code long};
     * therefore, the full range of {@code long} values cannot be accurately represented. If you need
     * to set a context attribute to a numeric value with more significant digits than will fit in a
     * {@code double}, it is best to encode it as a string.
     */
    public static final Converter<java.lang.Long> Long = new Converter<java.lang.Long>() {
      public LDValue fromType(java.lang.Long value) {
        return value == null ? LDValue.ofNull() : LDValue.of(value.longValue());
      }
      public java.lang.Long toType(LDValue value) {
        return java.lang.Long.valueOf(value.longValue());
      }
    };
    
    /**
     * A {@link LDValue.Converter} for floats.
     */
    public static final Converter<java.lang.Float> Float = new Converter<java.lang.Float>() {
      public LDValue fromType(java.lang.Float value) {
        return value == null ? LDValue.ofNull() : LDValue.of(value.floatValue());
      }
      public java.lang.Float toType(LDValue value) {
        return java.lang.Float.valueOf(value.floatValue());
      }
    };
    
    /**
     * A {@link LDValue.Converter} for doubles.
     */
    public static final Converter<java.lang.Double> Double = new Converter<java.lang.Double>() {
      public LDValue fromType(java.lang.Double value) {
        return value == null ? LDValue.ofNull() : LDValue.of(value.doubleValue());
      }
      public java.lang.Double toType(LDValue value) {
        return java.lang.Double.valueOf(value.doubleValue());
      }
    };
    
    /**
     * A {@link LDValue.Converter} for strings.
     */
    public static final Converter<java.lang.String> String = new Converter<java.lang.String>() {
      public LDValue fromType(java.lang.String value) {
        return LDValue.of(value);
      }
      public java.lang.String toType(LDValue value) {
        return value.stringValue();
      }
    };
  }
}
