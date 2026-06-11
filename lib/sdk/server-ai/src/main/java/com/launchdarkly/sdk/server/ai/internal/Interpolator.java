package com.launchdarkly.sdk.server.ai.internal;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.server.ai.internal.mustache.Mustache;
import com.launchdarkly.sdk.server.ai.internal.mustache.Template;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders AI Config message and instruction templates using Mustache, following the cross-SDK
 * interpolation policy shared with other SDKs:
 * <ul>
 *   <li><b>No HTML escaping.</b> The escape function is the identity, so {@code {{x}}} and
 *       {@code {{{x}}}} render identically and values are emitted verbatim.</li>
 *   <li><b>Missing and null variables render as the empty string</b> rather than throwing or
 *       leaving the placeholder in place.</li>
 *   <li><b>The reserved {@code ldctx} variable</b> is derived from the evaluation context and is
 *       merged in last, so it always overrides any caller-supplied {@code ldctx}. Context
 *       attributes are addressable as {@code {{ldctx.key}}}, {@code {{ldctx.name}}}, and so on.</li>
 * </ul>
 * <p>
 * Compiled templates are cached, keyed by template text. The class is thread-safe: the Mustache
 * compiler is immutable once configured, compiled {@link Template}s are safe for concurrent
 * execution, and the cache is a {@link ConcurrentHashMap}.
 * <p>
 * This class is an internal implementation detail and is not part of the supported API.
 */
public final class Interpolator {
  private final Mustache.Compiler compiler;
  private final ConcurrentHashMap<String, Template> templateCache = new ConcurrentHashMap<>();

  /**
   * Creates an interpolator with the cross-SDK escaping policy.
   */
  public Interpolator() {
    // defaultValue("") makes both missing variables and variables that resolve to null render as
    // the empty string (it sets jmustache's missingIsNull=true and nullValue=""). escapeHTML(false)
    // emits values verbatim, matching the JS/Python SDKs.
    this.compiler = Mustache.compiler()
        .escapeHTML(false)
        .defaultValue("");
  }

  /**
   * Renders a template with the given variables and evaluation context.
   *
   * @param template the template text; if {@code null} the result is {@code null}
   * @param variables caller-supplied variables; may be {@code null}
   * @param context the evaluation context, exposed to the template as {@code ldctx}; may be
   *     {@code null}
   * @return the rendered string, or {@code null} if {@code template} is {@code null}
   */
  public String interpolate(String template, Map<String, Object> variables, LDContext context) {
    if (template == null) {
      return null;
    }
    Map<String, Object> merged = new HashMap<>();
    if (variables != null) {
      merged.putAll(variables);
    }
    // ldctx is added last so it always wins over any caller-supplied "ldctx" entry.
    merged.put("ldctx", contextToMap(context));
    return render(template, merged);
  }

  /**
   * Renders a template with an already-assembled variable map (no {@code ldctx} injection).
   *
   * @param template the template text; if {@code null} the result is {@code null}
   * @param variables the variables; may be {@code null}
   * @return the rendered string, or {@code null} if {@code template} is {@code null}
   */
  public String interpolate(String template, Map<String, Object> variables) {
    if (template == null) {
      return null;
    }
    return render(template, variables == null ? new HashMap<String, Object>() : variables);
  }

  private String render(String template, Map<String, Object> variables) {
    Template compiled = templateCache.computeIfAbsent(template, compiler::compile);
    return compiled.execute(variables);
  }

  /**
   * Encodes the evaluation context directly into the nested map structure exposed to templates as
   * {@code ldctx}, without round-tripping through JSON serialization. A single-kind context becomes
   * a map of its attributes; a multi-kind context becomes
   * {@code {"kind":"multi", "key":<fully-qualified key>, <kind>: {...}}} with one nested map per
   * individual context.
   */
  private static Map<String, Object> contextToMap(LDContext context) {
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
          // Mirror LaunchDarkly's standard context JSON: the per-kind objects nested under a
          // multi-kind context omit "kind" because it is already implied by the property key.
          map.put(individual.getKind().toString(), singleContextToMap(individual, false));
        }
      }
      return map;
    }
    return singleContextToMap(context, true);
  }

  private static Map<String, Object> singleContextToMap(LDContext context, boolean includeKind) {
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
    // (depth-capped) so nested objects/arrays remain addressable from templates.
    for (String attribute : context.getCustomAttributeNames()) {
      map.put(attribute, LDValueConverter.toJavaObject(context.getValue(attribute)));
    }
    return map;
  }
}
