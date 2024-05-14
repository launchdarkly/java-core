package com.launchdarkly.sdk;

import com.google.gson.annotations.JsonAdapter;
import com.launchdarkly.sdk.json.JsonSerializable;

/**
 * A string identifier provided by the application to describe what kind of entity an
 * {@link LDContext} represents.
 * <p>
 * The type is a simple wrapper for a String. Using a type that is not just String
 * makes it clearer where a context kind is expected or returned in the SDK API, so it
 * cannot be confused with other important strings such as the context key. To convert
 * a literal string to this type, use the factory method {@link #of(String)}.
 * <p>
 * The meaning of the context kind is completely up to the application. Validation rules are
 * as follows:
 * <ul>
 * <li> It may only contain letters, numbers, and the characters ".", "_", and "-". </li>
 * <li> It cannot equal the literal string "kind". </li>
 * <li> For a single-kind context, it cannot equal "multi".
 * </ul>
 * <p>
 * If no kind is specified, the default is "user" (the constant {@link #DEFAULT}).
 * <p>
 * For a multi-kind context (see {@link LDContext#createMulti(LDContext...)}), the kind of
 * the top-level LDContext is always "multi" (the constant {@link #MULTI}); there is a 
 * specific Kind for each of the contexts contained within it.
 * <p>
 * To learn more, read <a href="https://docs.launchdarkly.com/home/contexts">the
 * documentation</a>.
 */
@JsonAdapter(ContextKindTypeAdapter.class)
public final class ContextKind implements Comparable<ContextKind>, JsonSerializable {
  /**
   * A constant for the default kind of "user".
   */
  public static final ContextKind DEFAULT = new ContextKind("user");
  
  /**
   * A constant for the kind that all multi-kind contexts have.
   */
  public static final ContextKind MULTI = new ContextKind("multi");
  
  private final String kindName;
  
  private ContextKind(String kindName) {
    this.kindName = kindName;
  }
  
  /**
   * Constructor from a string value.
   * <p>
   * A value of null or "" will be changed to {@link #DEFAULT}.
   * 
   * @param stringValue the string value
   * @return a ContextKind
   */
  public static ContextKind of(String stringValue) {
    if (stringValue == null || stringValue.isEmpty() || stringValue.equals(DEFAULT.kindName)) {
      return DEFAULT;
    }
    if (stringValue.equals(MULTI.kindName)) {
      return MULTI;
    }
    return new ContextKind(stringValue);
  }
  
  /**
   * True if this is equal to {@link #DEFAULT} ("user").
   * @return true if this is the default kind
   */
  public boolean isDefault() {
    return this == DEFAULT; // can use == here because of() ensures there's only one instance with that value
  }
  
  /**
   * Returns the string value of the context kind. This is never null.
   */
  @Override
  public String toString() {
    return kindName;
  }
  
  @Override
  public boolean equals(Object other) {
    return other instanceof ContextKind &&
        (this == other || kindName.equals(((ContextKind)other).kindName));
  }
  
  @Override
  public int hashCode() {
    return kindName.hashCode();
  }
  
  String validateAsSingleKind() {
    if (isDefault()) {
      return null;
    }
    if (this == MULTI) {
      return Errors.CONTEXT_KIND_MULTI_FOR_SINGLE;
    }
    if (kindName.equals("kind")) {
      return Errors.CONTEXT_KIND_CANNOT_BE_KIND;
    }
    for (int i = 0; i < kindName.length(); i++) {
      char ch = kindName.charAt(i);
      if ((ch < 'a' || ch > 'z') && (ch < 'A' || ch > 'Z') && (ch < '0' || ch > '9') &&
          ch != '.' && ch != '_' && ch != '-')
      {
        return Errors.CONTEXT_KIND_INVALID_CHARS;
      }
    }
    return null;
  }

  @Override
  public int compareTo(ContextKind o) {
    return kindName.compareTo(o.kindName);
  }
}
