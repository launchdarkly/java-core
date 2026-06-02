package com.launchdarkly.sdk.server.ai.internal;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Mustache.Compiler;
import com.samskivert.mustache.Mustache.Escaper;

import java.util.Map;

/**
 * Internal helper that renders Mustache templates for AI Config message and instruction
 * interpolation.
 * <p>
 * The renderer is configured to match the behavior of the Python reference SDK (which uses the
 * {@code chevron} library):
 * <ul>
 *   <li>Missing or {@code null} variables render as the empty string rather than raising an error.</li>
 *   <li>{@code {{ value }}} tags HTML-escape {@code &amp;}, {@code <}, {@code >}, and {@code "} (and
 *       only those characters), while {@code {{{ value }}}} tags emit the raw value.</li>
 * </ul>
 * <p>
 * This class is for internal use only and is not part of the supported public API.
 */
public final class Interpolator {
  /**
   * Escapes exactly the characters that the Python {@code chevron} library escapes, so that
   * interpolated prompts are byte-for-byte compatible across SDKs.
   */
  private static final Escaper CHEVRON_ESCAPER = raw ->
      raw.replace("&", "&amp;")
          .replace("<", "&lt;")
          .replace(">", "&gt;")
          .replace("\"", "&quot;");

  // defaultValue("") makes both null-valued and entirely-missing variables render as the empty
  // string (it sets nullValue="" and missingIsNull=true). A trailing nullValue("") must NOT be
  // chained here, as that would reset missingIsNull and cause missing variables to throw.
  private static final Compiler COMPILER = Mustache.compiler()
      .escapeHTML(true)
      .withEscaper(CHEVRON_ESCAPER)
      .defaultValue("");

  private Interpolator() {
  }

  /**
   * Renders a Mustache template against the supplied variables.
   *
   * @param template the template string
   * @param variables the variables available to the template
   * @return the rendered string
   */
  public static String interpolate(String template, Map<String, Object> variables) {
    return COMPILER.compile(template).execute(variables);
  }
}
