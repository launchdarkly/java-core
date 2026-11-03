package com.launchdarkly.sdk.server.integrations;

import java.time.Duration;

/**
 * Integration between the LaunchDarkly SDK and file-based flag overrides.
 * <p>
 * Flag overrides are currently experimental and subject to change.
 * <p>
 * Overrides are flag and segment definitions that take precedence over data received from
 * LaunchDarkly at evaluation time, on a per-key basis. They exist for resilience during an
 * incident. An operator can force one or more flags to a known state on a running application,
 * whether or not the application can reach LaunchDarkly. The override stays in effect until the
 * operator removes it. Flags not present in the override data are completely unaffected.
 * <p>
 * This class provides one override source: {@link #source()}, which reads overrides from local
 * files and reloads them as the files change. Configure it with the data system builder:
 * <pre><code>
 *     LDConfig config = new LDConfig.Builder()
 *         .dataSystem(Components.dataSystem().defaultMode()
 *             .overrides(FileOverrides.source().filePaths("/etc/launchdarkly/overrides.json")))
 *         .build();
 * </code></pre>
 * <p>
 * An evaluation that an override affects is marked. The marking is direct or transitive: it
 * applies when the evaluated flag, a prerequisite at any depth, or a segment read during the
 * evaluation came from the override layer. {@link com.launchdarkly.sdk.EvaluationReason#isOverrideAffected()}
 * reports the marking. Marked evaluations appear in analytics summary events only, under separate
 * counters, so LaunchDarkly can distinguish them. They produce no individual evaluation events.
 *
 * @see FileOverrideSourceBuilder
 * @see DataSystemBuilder#overrides(com.launchdarkly.sdk.server.subsystems.ComponentConfigurer)
 * @since 7.18.0
 */
public abstract class FileOverrides {
  /**
   * Selects how the file-based override source learns that a file changed. The two modes are
   * alternatives. Flag overrides are currently experimental and subject to change.
   *
   * @see FileOverrideSourceBuilder#changeDetection(ChangeDetection)
   */
  public enum ChangeDetection {
    /**
     * The source examines the files on a fixed interval and reloads when the modification time or
     * the size of a file changes. Polling works on every file system, including network mounts and
     * directories whose contents are swapped through symbolic links, as Kubernetes does for mounted
     * ConfigMaps. It is the default.
     */
    POLLING,

    /**
     * The source reloads in response to file system change notifications. It reacts faster than
     * polling. It depends on notifications, which some file systems do not deliver reliably.
     */
    WATCHING
  }

  /**
   * The interval at which the file source examines the files for changes in
   * {@link ChangeDetection#POLLING} mode when no interval was specified. Because the source reads
   * local files rather than contacting a service, a short interval keeps an override responsive
   * during an incident at negligible cost.
   */
  public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);

  /**
   * The shortest allowed polling interval. A configured interval below this is raised to it. The
   * minimum exists only to prevent a pathological tight loop over the file system.
   */
  public static final Duration MINIMUM_POLL_INTERVAL = Duration.ofSeconds(1);

  private FileOverrides() {}

  /**
   * Creates a {@link FileOverrideSourceBuilder} for a file-based override source. The source reads
   * flag and segment overrides from one or more local files and reloads them as the files change.
   * <p>
   * The files use the same document format as the file data source (see {@link FileData}): each
   * file is a JSON or YAML document with optional {@code "flags"}, {@code "flagValues"}, and
   * {@code "segments"} members. A file whose name ends in {@code .yaml} or {@code .yml}, in any case,
   * is parsed as YAML, and any other file is parsed as JSON. This differs from the file data source,
   * which parses a file as JSON when it starts with <code>'{'</code> and as YAML otherwise. A
   * {@code "flagValues"} entry is expanded into a full flag
   * definition that is on and serves the given value as its single variation by fallthrough for
   * every context. Such a flag is not available to client-side SDKs, so
   * {@link com.launchdarkly.sdk.server.LDClient#allFlagsState} with
   * {@link com.launchdarkly.sdk.server.FlagsStateOption#CLIENT_SIDE_ONLY} leaves it out, even when
   * the flag it overrides is available to them. To override such a flag, define it in
   * {@code "flags"} with {@code "clientSide": true}.
   * When multiple files are configured, their entries are combined in the configured order, and
   * the duplicate keys handling decides which file wins for a key that appears more than once.
   * <p>
   * A reload replaces the entire override set, so removing an entry from the files removes the
   * override. A configured file that does not exist contributes no overrides. Deleting a file
   * therefore removes its overrides, and deleting every file removes them all. A file that exists
   * but cannot be read or parsed makes that whole reload fail: the previously loaded overrides
   * stay in effect, the source logs the failure, retries after a short delay, and recovers on its
   * own once the file is readable again.
   * <p>
   * A file can be rewritten in place. To remove a file's overrides, delete the file or write a
   * document with no entries, such as <code>{}</code>. A JSON file with no content is not a valid
   * document: it fails to load, and the overrides in effect stay. A file whose content is in effect
   * and that is found empty, or with YAML content that holds no document, such as only a comment, may
   * be in the middle of a rewrite. The source then keeps the overrides in effect until the file has
   * stayed so, without being written, for about a second, or for at most about five seconds while the
   * file keeps being rewritten. Changes to the other files wait too, because a reload applies all the
   * files or none. After that, an empty YAML file removes its overrides, while an empty JSON file fails to
   * load and the overrides in effect stay. YAML that is cut off between two entries can still parse,
   * so for a YAML file, replacing its entire contents in one operation, such as renaming a fully
   * written temporary file over it, avoids briefly applying only a part of it.
   * <p>
   * Whenever the set of overrides in effect changes, including at startup, the source logs the
   * overrides in effect and what each configured file supplied, at Info level.
   * <p>
   * By default the source polls the files for changes once per second. See
   * {@link FileOverrideSourceBuilder#changeDetection(ChangeDetection)} and
   * {@link FileOverrideSourceBuilder#pollInterval(Duration)}.
   * <p>
   * Flag overrides are currently experimental and subject to change.
   *
   * @return a builder for the file-based override source
   */
  public static FileOverrideSourceBuilder source() {
    return new FileOverrideSourceBuilder();
  }
}
