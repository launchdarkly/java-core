package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.json.JsonSerializable;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.launchdarkly.sdk.server.JsonHelpers.gsonInstanceWithNullsAllowed;

/**
 * A snapshot of the state of all feature flags with regard to a specific user, generated by
 * calling {@link LDClientInterface#allFlagsState(com.launchdarkly.sdk.LDContext, FlagsStateOption...)}.
 * <p>
 * LaunchDarkly defines a standard JSON encoding for this object, suitable for
 * <a href="https://docs.launchdarkly.com/sdk/features/bootstrapping#javascript">bootstrapping</a>
 * the LaunchDarkly JavaScript browser SDK. You can convert it to JSON in any of these ways:
 * <ol>
 * <li> With {@link com.launchdarkly.sdk.json.JsonSerialization}.
 * <li> With Gson, if and only if you configure your {@code Gson} instance with
 * {@link com.launchdarkly.sdk.json.LDGson}.
 * <li> With Jackson, if and only if you configure your {@code ObjectMapper} instance with
 * {@link com.launchdarkly.sdk.json.LDJackson}.
 * </ol>
 * 
 * @since 4.3.0
 */
@JsonAdapter(FeatureFlagsState.JsonSerialization.class)
public final class FeatureFlagsState implements JsonSerializable {
  private final ImmutableMap<String, FlagMetadata> flagMetadata;
  private final boolean valid;
    
  static class FlagMetadata {
    final LDValue value;
    final Integer variation;
    final EvaluationReason reason;
    final Integer version;
    final boolean trackEvents;
    final boolean trackReason;
    final Long debugEventsUntilDate;
    final List<String> prerequisites;
    
    FlagMetadata(LDValue value, Integer variation, EvaluationReason reason, Integer version,
                 boolean trackEvents, boolean trackReason, Long debugEventsUntilDate, List<String> prerequisites) {
      this.value = LDValue.normalize(value);
      this.variation = variation;
      this.reason = reason;
      this.version = version;
      this.trackEvents = trackEvents;
      this.trackReason = trackReason;
      this.debugEventsUntilDate = debugEventsUntilDate;
      this.prerequisites = prerequisites;
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof FlagMetadata) {
        FlagMetadata o = (FlagMetadata)other;
        return value.equals(o.value) &&
            Objects.equals(variation, o.variation) &&
            Objects.equals(reason, o.reason) &&
            Objects.equals(version, o.version) &&
            trackEvents == o.trackEvents &&
            trackReason == o.trackReason &&
            Objects.equals(debugEventsUntilDate, o.debugEventsUntilDate) &&
            Objects.equals(prerequisites, o.prerequisites);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(value, variation, reason, version, trackEvents, trackReason, debugEventsUntilDate, prerequisites);
    }
  }
  
  private FeatureFlagsState(ImmutableMap<String, FlagMetadata> flagMetadata, boolean valid) {
    this.flagMetadata = flagMetadata;
    this.valid = valid;
  }
  
  /**
   * Returns a {@link Builder} for creating instances.
   * <p>
   * Application code will not normally use this builder, since the SDK creates its own instances.
   * However, it may be useful in testing, to simulate values that might be returned by
   * {@link LDClient#allFlagsState(com.launchdarkly.sdk.LDContext, FlagsStateOption...)}.
   * 
   * @param options the same {@link FlagsStateOption}s, if any, that would be passed to
   *   {@link LDClient#allFlagsState(com.launchdarkly.sdk.LDContext, FlagsStateOption...)}
   * @return a builder object
   * @since 5.6.0
   */
  public static Builder builder(FlagsStateOption... options) {
    return new Builder(options);
  }
  
  /**
   * Returns true if this object contains a valid snapshot of feature flag state, or false if the
   * state could not be computed (for instance, because the client was offline or there was no user).
   * @return true if the state is valid
   */
  public boolean isValid() {
    return valid;
  }
  
  /**
   * Returns the value of an individual feature flag at the time the state was recorded.
   * @param key the feature flag key
   * @return the flag's JSON value; {@link LDValue#ofNull()} if the flag returned the default value;
   *   {@code null} if there was no such flag
   */
  public LDValue getFlagValue(String key) {
    FlagMetadata data = flagMetadata.get(key);
    return data == null ? null : data.value;
  }

  /**
   * Returns the evaluation reason for an individual feature flag at the time the state was recorded.
   * @param key the feature flag key
   * @return an {@link EvaluationReason}; null if reasons were not recorded, or if there was no such flag
   */
  public EvaluationReason getFlagReason(String key) {
    FlagMetadata data = flagMetadata.get(key);
    return data == null ? null : data.reason;
  }
  
  /**
   * Returns a map of flag keys to flag values. If a flag would have evaluated to the default value,
   * its value will be null.
   * <p>
   * The returned map is unmodifiable.
   * <p>
   * Do not use this method if you are passing data to the front end to "bootstrap" the JavaScript client.
   * Instead, serialize the FeatureFlagsState object to JSON using {@code Gson.toJson()} or {@code Gson.toJsonTree()}.
   * @return an immutable map of flag keys to JSON values
   */
  public Map<String, LDValue> toValuesMap() {
    return Maps.transformValues(flagMetadata, v -> v.value);
  }
  
  @Override
  public boolean equals(Object other) {
    if (other instanceof FeatureFlagsState) {
      FeatureFlagsState o = (FeatureFlagsState)other;
      return flagMetadata.equals(o.flagMetadata) &&
          valid == o.valid;
    }
    return false;
  }
  
  @Override
  public int hashCode() {
    return Objects.hash(flagMetadata, valid);
  }
  
  /**
   * A builder for a {@link FeatureFlagsState} instance.
   * <p>
   * Application code will not normally use this builder, since the SDK creates its own instances.
   * However, it may be useful in testing, to simulate values that might be returned by
   * {@link LDClient#allFlagsState(com.launchdarkly.sdk.LDContext, FlagsStateOption...)}.
   *
   * @since 5.6.0
   */
  public static class Builder {
    private ImmutableMap.Builder<String, FlagMetadata> flagMetadata = ImmutableMap.builder();
    private final boolean saveReasons;
    private final boolean detailsOnlyForTrackedFlags;
    private boolean valid = true;

    private Builder(FlagsStateOption... options) {
      saveReasons = FlagsStateOption.hasOption(options, FlagsStateOption.WITH_REASONS);
      detailsOnlyForTrackedFlags = FlagsStateOption.hasOption(options, FlagsStateOption.DETAILS_ONLY_FOR_TRACKED_FLAGS);
    }
    
    /**
     * Sets the {@link FeatureFlagsState#isValid()} property. This is true by default.
     * 
     * @param valid the new property value
     * @return the builder
     */
    public Builder valid(boolean valid) {
      this.valid = valid;
      return this;
    }
    
    /**
     * Adds data to the builder representing the result of a feature flag evaluation.
     * <p>
     * The {@code flagVersion}, {@code trackEvents}, and {@code debugEventsUntilDate} parameters are
     * normally generated internally by the SDK; they are used if the {@link FeatureFlagsState} data
     * has been passed to front-end code, to control how analytics events are generated by the front
     * end. If you are using this builder in back-end test code, those values are unimportant.
     * 
     * @param flagKey the feature flag key
     * @param value the evaluated value
     * @param variationIndex the evaluated variation index
     * @param reason the evaluation reason
     * @param flagVersion the current flag version 
     * @param trackEvents true if full event tracking is turned on for this flag
     * @param debugEventsUntilDate if set, event debugging is turned until this time (millisecond timestamp) 
     * @return the builder
     */
    public Builder add(
        String flagKey,
        LDValue value,
        Integer variationIndex,
        EvaluationReason reason,
        int flagVersion,
        boolean trackEvents,
        Long debugEventsUntilDate,
        List<String> prerequisites
        ) {
      return add(flagKey, value, variationIndex, reason, flagVersion, trackEvents, false, debugEventsUntilDate, prerequisites);
    }
    
    /**
     * Adds data to the builder representing the result of a feature flag evaluation.
     * <p>
     * The {@code flagVersion}, {@code trackEvents}, and {@code debugEventsUntilDate} parameters are
     * normally generated internally by the SDK; they are used if the {@link FeatureFlagsState} data
     * has been passed to front-end code, to control how analytics events are generated by the front
     * end. If you are using this builder in back-end test code, those values are unimportant.
     * 
     * @param flagKey the feature flag key
     * @param value the evaluated value
     * @param variationIndex the evaluated variation index
     * @param reason the evaluation reason
     * @param flagVersion the current flag version
     * @param trackEvents true if full event tracking is turned on for this flag
     * @param trackReason true if evaluation reasons must be included due to experimentation
     * @param debugEventsUntilDate if set, event debugging is turned until this time (millisecond timestamp)
     * @return the builder
     */
    // TODO: add param to docs
    public Builder add(
        String flagKey,
        LDValue value,
        Integer variationIndex,
        EvaluationReason reason,
        int flagVersion,
        boolean trackEvents,
        boolean trackReason,
        Long debugEventsUntilDate,
        List<String> prerequisites
        ) {
      final boolean flagIsTracked = trackEvents ||
          (debugEventsUntilDate != null && debugEventsUntilDate > System.currentTimeMillis());
      final boolean wantDetails = !detailsOnlyForTrackedFlags || flagIsTracked;
      FlagMetadata data = new FlagMetadata(
          value,
          variationIndex,
          (saveReasons && wantDetails) || trackReason ? reason : null,
          wantDetails ? Integer.valueOf(flagVersion) : null,
          trackEvents,
          trackReason,
          debugEventsUntilDate,
          prerequisites);
      flagMetadata.put(flagKey, data);
      return this;
    }
    
    Builder addFlag(DataModel.FeatureFlag flag, EvalResult eval) {
      return add(
          flag.getKey(),
          eval.getValue(),
          eval.isNoVariation() ? null : eval.getVariationIndex(),
          eval.getReason(),
          flag.getVersion(),
          flag.isTrackEvents() || eval.isForceReasonTracking(),
          eval.isForceReasonTracking(),
          flag.getDebugEventsUntilDate(),
          eval.getPrerequisiteEvalRecords() == null ? null : eval.getPrerequisiteEvalRecords().stream()
              .filter(record -> record.prereqOfFlag.getKey() == flag.getKey())  // only include top level prereqs
              .map(record -> record.flag.getKey()) // map from prereq record to prereq key
              .collect(Collectors.toList())
          );
    }
    
    /**
     * Returns an object created from the builder state.
     * 
     * @return an immutable {@link FeatureFlagsState}
     */
    public FeatureFlagsState build() {
      return new FeatureFlagsState(flagMetadata.build(), valid);
    }
  }
  
  static class JsonSerialization extends TypeAdapter<FeatureFlagsState> {
    @Override
    public void write(JsonWriter out, FeatureFlagsState state) throws IOException {
      out.beginObject();
      
      for (Map.Entry<String, FlagMetadata> entry: state.flagMetadata.entrySet()) {
        out.name(entry.getKey());
        gsonInstanceWithNullsAllowed().toJson(entry.getValue().value, LDValue.class, out);
      }
      
      out.name("$flagsState");
      out.beginObject();
      for (Map.Entry<String, FlagMetadata> entry: state.flagMetadata.entrySet()) {
        out.name(entry.getKey());
        FlagMetadata meta = entry.getValue();
        out.beginObject();
        // Here we're serializing FlagMetadata properties individually because if we rely on
        // Gson's reflection mechanism, it won't reliably drop null properties (that only works
        // if the destination really is Gson, not if a Jackson adapter is being used).
        if (meta.variation != null) {
          out.name("variation");
          out.value(meta.variation.intValue());
        }
        if (meta.reason != null) {
          out.name("reason");
          gsonInstanceWithNullsAllowed().toJson(meta.reason, EvaluationReason.class, out);
        }
        if (meta.version != null) {
          out.name("version");
          out.value(meta.version.intValue());
        }
        if (meta.trackEvents) {
          out.name("trackEvents");
          out.value(meta.trackEvents);
        }
        if (meta.trackReason) {
          out.name("trackReason");
          out.value(meta.trackReason);
        }
        if (meta.debugEventsUntilDate != null) {
          out.name("debugEventsUntilDate");
          out.value(meta.debugEventsUntilDate.longValue());
        }
        if (meta.prerequisites != null && !meta.prerequisites.isEmpty()) {
          out.name("prerequisites");
          out.beginArray();
          for (String s: meta.prerequisites) {
            out.value(s);
          }
          out.endArray();
        }
        out.endObject();
      }
      out.endObject();
      
      out.name("$valid");
      out.value(state.valid);
      
      out.endObject();
    }

    // There isn't really a use case for deserializing this, but we have to implement it
    @Override
    public FeatureFlagsState read(JsonReader in) throws IOException {
      Map<String, LDValue> flagValues = new HashMap<>();
      Map<String, FlagMetadata> flagMetadataWithoutValues = new HashMap<>();
      boolean valid = true;
      in.beginObject();
      while (in.hasNext()) {
        String name = in.nextName();
        if (name.equals("$flagsState")) {
          in.beginObject();
          while (in.hasNext()) {
            String metaName = in.nextName();
            FlagMetadata meta = gsonInstanceWithNullsAllowed().fromJson(in, FlagMetadata.class);
            flagMetadataWithoutValues.put(metaName, meta);
          }
          in.endObject();
        } else if (name.equals("$valid")) {
          valid = in.nextBoolean();
        } else {
          LDValue value = gsonInstanceWithNullsAllowed().fromJson(in, LDValue.class);
          flagValues.put(name, value);
        }
      }
      in.endObject();
      ImmutableMap.Builder<String, FlagMetadata> allFlagMetadata = ImmutableMap.builder();
      for (Map.Entry<String, LDValue> e: flagValues.entrySet()) {
        FlagMetadata m0 = flagMetadataWithoutValues.get(e.getKey());
        if (m0 != null) {
          FlagMetadata m1 = new FlagMetadata(
              e.getValue(),
              m0.variation,
              m0.reason,
              m0.version,
              m0.trackEvents,
              m0.trackReason,
              m0.debugEventsUntilDate,
              m0.prerequisites);
          allFlagMetadata.put(e.getKey(), m1);
        }
      }
      return new FeatureFlagsState(allFlagMetadata.build(), valid);
    }
  }
}
