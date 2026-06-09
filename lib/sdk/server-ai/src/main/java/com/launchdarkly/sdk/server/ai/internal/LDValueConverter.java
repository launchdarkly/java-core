package com.launchdarkly.sdk.server.ai.internal;

import com.launchdarkly.sdk.ArrayBuilder;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.LDValueType;
import com.launchdarkly.sdk.ObjectBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts an {@link LDValue} tree into a tree of plain Java values
 * ({@link String}, {@link Long}, {@link Double}, {@link Boolean}, {@link List}, {@link Map}, or
 * {@code null}).
 * <p>
 * This is used to expose AI Config {@code model.parameters} / {@code model.custom} and tool
 * parameters to callers without leaking the {@link LDValue} type onto the public surface.
 * <p>
 * Conversion is defensive: it never throws on malformed or pathological input. Numbers are decoded
 * to {@link Long} when they are mathematically integral and within the IEEE-754 exact-integer range
 * ({@code |value| <= 2^53}); otherwise they are decoded to {@link Double}. Whole numbers outside
 * {@code ±2^53} cannot be represented exactly and are returned as the nearest {@link Double}.
 * Conversion depth is capped (see {@link #MAX_DEPTH}); values nested more deeply than the cap are
 * dropped (rendered as {@code null}) to bound stack usage on adversarial input.
 * <p>
 * This class is an internal implementation detail and is not part of the supported API.
 */
public final class LDValueConverter {
  /**
   * Maximum nesting depth converted before deeper values are dropped.
   */
  public static final int MAX_DEPTH = 100;

  /**
   * Largest magnitude of a whole number that a {@code double} can represent exactly.
   */
  private static final double MAX_EXACT_INTEGER = 9007199254740992.0; // 2^53

  private LDValueConverter() {
  }

  /**
   * Converts an {@link LDValue} to a plain Java value.
   *
   * @param value the value to convert; may be {@code null}
   * @return the converted value, or {@code null} if the input is {@code null} or JSON null
   */
  public static Object toJavaObject(LDValue value) {
    return convert(value, 0);
  }

  /**
   * Converts an {@link LDValue} object to an unmodifiable {@code Map<String, Object>}.
   *
   * @param value the value to convert
   * @return the converted map; {@code null} if {@code value} is not a JSON object
   */
  public static Map<String, Object> toMap(LDValue value) {
    if (value == null || value.getType() != LDValueType.OBJECT) {
      return null;
    }
    Object converted = convert(value, 0);
    if (converted instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) converted;
      return map;
    }
    return null;
  }

  /**
   * Converts a plain Java value back into an {@link LDValue}.
   * <p>
   * This is the inverse of {@link #toJavaObject(LDValue)} and is used to render a caller-supplied
   * default config back into the JSON flag-value shape so it can flow through the base SDK's
   * variation call. Supported inputs are {@link LDValue}, {@link String}, {@link Boolean},
   * {@link Number}, {@link Map} (with string keys), and {@link Iterable}; any other type (or a
   * {@code null}) becomes {@link LDValue#ofNull()}. Conversion depth is capped (see
   * {@link #MAX_DEPTH}); values nested more deeply are dropped to {@code null}.
   *
   * @param value the value to convert; may be {@code null}
   * @return the equivalent {@link LDValue}, never {@code null}
   */
  public static LDValue fromJavaObject(Object value) {
    return fromJavaObject(value, 0);
  }

  private static LDValue fromJavaObject(Object value, int depth) {
    if (value == null || depth >= MAX_DEPTH) {
      return LDValue.ofNull();
    }
    if (value instanceof LDValue) {
      return (LDValue) value;
    }
    if (value instanceof String) {
      return LDValue.of((String) value);
    }
    if (value instanceof Boolean) {
      return LDValue.of((Boolean) value);
    }
    if (value instanceof Integer) {
      return LDValue.of((Integer) value);
    }
    if (value instanceof Long) {
      return LDValue.of((Long) value);
    }
    if (value instanceof Number) {
      return LDValue.of(((Number) value).doubleValue());
    }
    if (value instanceof Map) {
      ObjectBuilder builder = LDValue.buildObject();
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        if (entry.getKey() != null) {
          builder.put(entry.getKey().toString(), fromJavaObject(entry.getValue(), depth + 1));
        }
      }
      return builder.build();
    }
    if (value instanceof Iterable) {
      ArrayBuilder builder = LDValue.buildArray();
      for (Object element : (Iterable<?>) value) {
        builder.add(fromJavaObject(element, depth + 1));
      }
      return builder.build();
    }
    return LDValue.ofNull();
  }

  private static Object convert(LDValue value, int depth) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (depth >= MAX_DEPTH) {
      return null;
    }

    LDValueType type = value.getType();
    switch (type) {
      case BOOLEAN:
        return value.booleanValue();
      case NUMBER:
        return convertNumber(value.doubleValue());
      case STRING:
        return value.stringValue();
      case ARRAY: {
        List<Object> list = new ArrayList<>(value.size());
        for (LDValue element : value.values()) {
          list.add(convert(element, depth + 1));
        }
        return Collections.unmodifiableList(list);
      }
      case OBJECT: {
        // LinkedHashMap to preserve field order for deterministic output.
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : value.keys()) {
          map.put(key, convert(value.get(key), depth + 1));
        }
        return Collections.unmodifiableMap(map);
      }
      case NULL:
      default:
        return null;
    }
  }

  private static Object convertNumber(double d) {
    if (!Double.isNaN(d) && !Double.isInfinite(d)
        && d == Math.rint(d) && Math.abs(d) <= MAX_EXACT_INTEGER) {
      return (long) d;
    }
    return d;
  }
}
