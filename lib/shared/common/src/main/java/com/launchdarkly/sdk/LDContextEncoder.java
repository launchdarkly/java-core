package com.launchdarkly.sdk;

import java.util.HashMap;
import java.util.Map;

/**
 * Encodes an {@link LDContext} into a plain nested {@code Map<String, Object>} structure without
 * round-tripping through JSON serialization. Leaf attribute values are converted by
 * {@link LDValueConverter}.
 * <p>
 * Output shape:
 * <ul>
 *   <li>A {@code null} or invalid context produces an empty map.</li>
 *   <li>A single-kind context produces a map containing {@code kind}, {@code key},
 *       {@code name} (only when non-null), {@code anonymous} (always present), and one entry for
 *       each custom attribute.</li>
 *   <li>A multi-kind context produces
 *       {@code {"kind":"multi", "key":<fullyQualifiedKey>, <kindName>:{...}, ...}} where each
 *       per-kind nested map omits {@code kind} (it is implied by the property key).</li>
 * </ul>
 */
public final class LDContextEncoder {

  private LDContextEncoder() {
  }

  /**
   * Encodes an {@link LDContext} into a plain nested {@code Map<String, Object>}.
   *
   * @param context the context to encode; may be {@code null}
   * @return the encoded map; never {@code null} (an empty map is returned for invalid or null input)
   */
  public static Map<String, Object> encode(LDContext context) {
    if (context == null || !context.isValid()) {
      return new HashMap<>();
    }
    if (context.isMultiple()) {
      Map<String, Object> map = new HashMap<>();
      map.put("kind", "multi");
      map.put("key", context.getFullyQualifiedKey());
      int count = context.getIndividualContextCount();
      for (int i = 0; i < count; i++) {
        LDContext individual = context.getIndividualContext(i);
        if (individual != null) {
          // Mirror LaunchDarkly's standard context JSON: per-kind objects nested under a
          // multi-kind context omit "kind" because it is already implied by the property key.
          map.put(individual.getKind().toString(), encodeSingle(individual, false));
        }
      }
      return map;
    }
    return encodeSingle(context, true);
  }

  private static Map<String, Object> encodeSingle(LDContext context, boolean includeKind) {
    Map<String, Object> map = new HashMap<>();
    if (includeKind) {
      map.put("kind", context.getKind().toString());
    }
    map.put("key", context.getKey());
    if (context.getName() != null) {
      map.put("name", context.getName());
    }
    map.put("anonymous", context.isAnonymous());
    // Custom attribute values can be arbitrary JSON; convert each LDValue to a plain Java value
    // (depth-capped) so nested objects/arrays are fully traversable.
    for (String attribute : context.getCustomAttributeNames()) {
      map.put(attribute, LDValueConverter.toJavaObject(context.getValue(attribute)));
    }
    return map;
  }
}
