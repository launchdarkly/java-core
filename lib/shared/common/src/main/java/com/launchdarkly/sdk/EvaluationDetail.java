package com.launchdarkly.sdk;

import com.google.gson.annotations.JsonAdapter;
import com.launchdarkly.sdk.json.JsonSerializable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An object returned by the SDK's "variation detail" methods such as {@code boolVariationDetail},
 * combining the result of a flag evaluation with an explanation of how it was calculated.
 * <p>
 * {@link EvaluationReason} can be converted to and from JSON in any of these ways:
 * <ol>
 * <li> With {@link com.launchdarkly.sdk.json.JsonSerialization}.
 * <li> With Gson, if and only if you configure your {@code Gson} instance with
 * {@link com.launchdarkly.sdk.json.LDGson}.
 * <li> With Jackson, if and only if you configure your {@code ObjectMapper} instance with
 * {@link com.launchdarkly.sdk.json.LDJackson}.
 * </ol>
 * 
 * <b>Note:</b> There is currently a limitation regarding deserialization for generic types.
 * If you use Gson, you must pass a `TypeToken` to specify the runtime type of
 * {@code EvaluationDetail<T>}, or else it will assume that `T` is `LDValue`. If you use either
 * {@code JsonSerialization} or Jackson, there is no way to specify the runtime type and you
 * will always get an {@code EvaluationDetail<LDValue>}. That is only for deserialization;
 * serialization will always use the correct value type.
 * 
 * @param <T> the type of the wrapped value
 */
@JsonAdapter(EvaluationDetailTypeAdapterFactory.class)
public final class EvaluationDetail<T> implements JsonSerializable {
  /**
   * If {@link #getVariationIndex()} is equal to this constant, it means no variation was chosen
   * (evaluation failed and returned a default value).
   */
  public static final int NO_VARIATION = -1;
  
  private static final Iterable<EvaluationDetail<?>> BOOLEAN_SINGLETONS = createBooleanSingletons();
  
  private final T value;
  private final int variationIndex;
  private final EvaluationReason reason;

  // Constructor is private to allow us to use different creation strategies in the factory method
  // (such as interning some commonly used instances).
  private EvaluationDetail(T value, int variationIndex, EvaluationReason reason) {
    this.value = value;
    this.variationIndex = variationIndex >= 0 ? variationIndex : NO_VARIATION;
    this.reason = reason;
  }

  /**
   * Factory method for an arbitrary value.
   * 
   * @param <T> the type of the value
   * @param value a value of the desired type
   * @param variationIndex a variation index, or {@link #NO_VARIATION} (any negative number will be
   *   changed to {@link #NO_VARIATION})
   * @param reason an {@link EvaluationReason} (should not be null)
   * @return an {@link EvaluationDetail}
   */
  @SuppressWarnings("unchecked")
  public static <T> EvaluationDetail<T> fromValue(T value, int variationIndex, EvaluationReason reason) {
    // Return an existing singleton if possible to avoid creating a lot of ephemeral objects for the
    // typical boolean flag cases.
    if (value != null && (value.getClass() == Boolean.class || value.getClass() == LDValueBool.class)) {
      for (EvaluationDetail<?> d: BOOLEAN_SINGLETONS) {
        if (d.value == value && d.variationIndex == variationIndex && d.reason == reason) {
          return (EvaluationDetail<T>)d;
        }
      }
    }
    return new EvaluationDetail<T>(value, variationIndex, reason);
  }
  
  /**
   * Shortcut for creating an instance with an error result.
   * 
   * @param errorKind the type of error
   * @param defaultValue the application default value
   * @return an {@link EvaluationDetail}
   */
  public static EvaluationDetail<LDValue> error(EvaluationReason.ErrorKind errorKind, LDValue defaultValue) {
    return new EvaluationDetail<LDValue>(LDValue.normalize(defaultValue), NO_VARIATION, EvaluationReason.error(errorKind));
  }
  
  /**
   * An object describing the main factor that influenced the flag evaluation value.
   * 
   * @return an {@link EvaluationReason}
   */
  public EvaluationReason getReason() {
    return reason;
  }

  /**
   * The index of the returned value within the flag's list of variations, e.g. 0 for the first variation,
   * or {@link #NO_VARIATION}.
   * 
   * @return the variation index if applicable
   */
  public int getVariationIndex() {
    return variationIndex;
  }

  /**
   * The result of the flag evaluation. This will be either one of the flag's variations or the default
   * value that was passed to the {@code variation} method.
   * 
   * @return the flag value
   */
  public T getValue() {
    return value;
  }
  
  /**
   * Returns true if the flag evaluation returned the default value, rather than one of the flag's
   * variations. If so, {@link #getVariationIndex()} will be {@link #NO_VARIATION}.
   * 
   * @return true if this is the default value
   */
  public boolean isDefaultValue() {
    return variationIndex < 0;
  }
  
  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (other instanceof EvaluationDetail) {
      @SuppressWarnings("unchecked")
      EvaluationDetail<T> o = (EvaluationDetail<T>)other;
      return Objects.equals(reason, o.reason) && variationIndex == o.variationIndex && Objects.equals(value, o.value);
    }
    return false;
  }
  
  @Override
  public int hashCode() {
    return Objects.hash(reason, variationIndex, value);
  }
  
  /**
   * Returns a simple string representation of this instance.
   * <p>
   * This is a convenience method for debugging and any other use cases where a human-readable string is
   * helpful. The exact format of the string is subject to change; if you need to make programmatic
   * decisions based on the object's properties, use other methods like {@link #getValue()}.
   */
  @Override
  public String toString() {
    return "{" + value + "," + variationIndex + "," + reason + "}";
  }
  
  private static Iterable<EvaluationDetail<?>> createBooleanSingletons() {
    // Boolean flags are very commonly used, so we'll generate likely combinations here because it's
    // better to iterate through a few more array elements than to create an instance. Note that the
    // internal evaluation logic will use LDValue whereas boolVariation() uses Boolean.
    List<EvaluationDetail<?>> ret = new ArrayList<>();
    
    // It's more common for false to be variation 0, so put that first 
    for (int iFalseVariation = 0; iFalseVariation < 2; iFalseVariation++) {
      // It's more common for the off variation to be variation 0, so put that first
      for (int iOffVariation = 0; iOffVariation < 2; iOffVariation++) {
        for (int iTruth = 0; iTruth < 2; iTruth++) {
          for (int iType = 0; iType < 2; iType++) {
            Object value = iType == 0 ? LDValue.of(iTruth == 1) : Boolean.valueOf(iTruth == 1);
            int variationIndex = iTruth == 0 ? iFalseVariation : (1 - iFalseVariation);
            EvaluationReason reason = variationIndex == iOffVariation ? EvaluationReason.off() : EvaluationReason.fallthrough();
            ret.add(new EvaluationDetail<Object>(value, variationIndex, reason));
          }
        }
      }
    }
    
    return ret;
  }
}
