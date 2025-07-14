package com.launchdarkly.sdk;

import com.google.gson.annotations.JsonAdapter;
import com.launchdarkly.sdk.json.JsonSerializable;
import com.launchdarkly.sdk.json.JsonSerialization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A collection of attributes that can be referenced in flag evaluations and analytics events.
 * <p>
 * LDContext is the newer replacement for the previous, less flexible {@link LDUser} type.
 * The current SDK still supports LDUser, but LDContext is now the preferred model and may
 * entirely replace LDUser in the future.
 * <p>
 * To create an LDContext of a single kind, such as a user, you may use {@link #create(String)}
 * or {@link #create(ContextKind, String)} when only the key matters; or, to specify other
 * attributes, use {@link #builder(String)}.
 * <p>
 * To create an LDContext with multiple kinds, use {@link #createMulti(LDContext...)} or
 * {@link #multiBuilder()}.
 * <p>
 * An LDContext can be in an error state if it was built with invalid attributes. See
 * {@link #isValid()} and {@link #getError()}.
 * <p>
 * LaunchDarkly defines a standard JSON encoding for contexts, used by the JavaScript SDK
 * and also in analytics events. {@link LDContext} can be converted to and from JSON in any of
 * these ways:
 * <ol>
 * <li> With {@link JsonSerialization}.
 * <li> With Gson, if and only if you configure your {@code Gson} instance with
 * {@link com.launchdarkly.sdk.json.LDGson}.
 * <li> With Jackson, if and only if you configure your {@code ObjectMapper} instance with
 * {@link com.launchdarkly.sdk.json.LDJackson}.
 * </ol>
 * <p>
 * To learn more about contexts, read <a href="https://docs.launchdarkly.com/home/contexts">the
 * documentation</a>.
 */
@JsonAdapter(LDContextTypeAdapter.class)
public final class LDContext implements JsonSerializable {
  static final String ATTR_KIND = "kind";
  static final String ATTR_KEY = "key";
  static final String ATTR_NAME = "name";
  static final String ATTR_ANONYMOUS = "anonymous";
  
  
  final String error;
  final ContextKind kind;
  final LDContext[] multiContexts;
  final String key;
  final String fullyQualifiedKey;
  final String name;
  final AttributeMap attributes;
  final boolean anonymous;
  final List<AttributeRef> privateAttributes;
  
  private LDContext(
      ContextKind kind,
      LDContext[] multiContexts,
      String key,
      String fullyQualifiedKey,
      String name,
      AttributeMap attributes,
      boolean anonymous,
      List<AttributeRef> privateAttributes
      ) {
    this.error = null;
    this.kind = kind == null ? ContextKind.DEFAULT : kind;
    this.multiContexts = multiContexts;
    this.key = key;
    this.fullyQualifiedKey = fullyQualifiedKey;
    this.name = name;
    this.attributes = attributes;
    this.anonymous = anonymous;
    this.privateAttributes = privateAttributes;
  }

  private LDContext(String error) {
    this.error = error;
    this.kind = null;
    this.multiContexts = null;
    this.key = "";
    this.fullyQualifiedKey = "";
    this.name = null;
    this.attributes = null;
    this.anonymous = false;
    this.privateAttributes = null;
  }
  
  // Internal factory method for single-kind contexts.
  static LDContext createSingle(
      ContextKind kind,
      String key,
      String name,
      AttributeMap attributes,
      boolean anonymous,
      List<AttributeRef> privateAttributes,
      boolean allowEmptyKey // allowEmptyKey is true only when deserializing old-style user JSON
      ) {
    if (kind != null) {
      String error = kind.validateAsSingleKind();
      if (error != null) {
        return failed(error);
      }
    }
    if (key == null || (key.isEmpty() && !allowEmptyKey)) {
      return failed(Errors.CONTEXT_NO_KEY);
    }
    String fullyQualifiedKey = kind.isDefault() ? key :
      (kind.toString() + ":" + escapeKeyForFullyQualifiedKey(key));
    return new LDContext(kind, null, key, fullyQualifiedKey, name, attributes, anonymous, privateAttributes);
  }
  
  // Internal factory method for multi-kind contexts - implements all of the validation logic
  // except for validating that there is more than one context. We take ownership of the list
  // that is passed in, so it is effectively immutable afterward; ContextMultiBuilder has
  // copy-on-write logic to manage that.
  static LDContext createMultiInternal(LDContext[] multiContexts) {
    List<String> errors = null;
    boolean duplicates = false;
    for (int i = 0; i < multiContexts.length; i++) {
      LDContext c = multiContexts[i];
      if (!c.isValid()) {
        if (errors == null) {
          errors = new ArrayList<String>();
        }
        errors.add(c.getError());
      } else {
        for (int j = 0; j < i; j++) {
          // since kind can be null in the malformed context case, need to do equality check with null safety.
          // Objects.equals handles the null equality case without a NPE.
          if (Objects.equals(multiContexts[j].getKind(), c.getKind())) {
            duplicates = true;
            break;
          }
        }
      }
    }
    if (duplicates) {
      if (errors == null) {
        errors = new ArrayList<String>();
      }
      errors.add(Errors.CONTEXT_KIND_MULTI_DUPLICATES);
    }
    
    if (errors != null) {
      StringBuilder s = new StringBuilder();
      for (String e: errors) {
        if (s.length() != 0) {
          s.append(", ");
        }
        s.append(e);
      }
      return failed(s.toString());
    }
    
    Arrays.sort(multiContexts, ByKindComparator.INSTANCE);
    StringBuilder fullKey = new StringBuilder();
    for (LDContext c: multiContexts) {
      if (fullKey.length() != 0) {
        fullKey.append(':');
      }
      fullKey.append(c.getKind().toString()).append(':').append(escapeKeyForFullyQualifiedKey(c.getKey()));
    }
    return new LDContext(ContextKind.MULTI, multiContexts, "", fullKey.toString(),
        null, null, false, null);
  }
  
  // Internal factory method for a context in an invalid state.
  static LDContext failed(String error) {
    return new LDContext(error);
  }
  
  /**
   * Creates a single-kind LDContext with a kind of {@link ContextKind#DEFAULT}} and the specified key.
   * <p>
   * To specify additional properties, use {@link #builder(String)}. To create a multi-kind
   * LDContext, use {@link #createMulti(LDContext...)} or {@link #multiBuilder()}. To create a
   * single-kind LDContext of a different kind than "user", use {@link #create(ContextKind, String)}.
   * 
   * @param key the context key
   * @return an LDContext
   * @see #create(ContextKind, String)
   * @see #builder(String)
   */
  public static LDContext create(String key) {
    return create(ContextKind.DEFAULT, key);
  }
  
  /**
   * Creates a single-kind LDContext with only the kind and keys specified.
   * <p>
   * To specify additional properties, use {@link #builder(ContextKind, String)}. To create a multi-kind
   * LDContext, use {@link #createMulti(LDContext...)} or {@link #multiBuilder()}.
   * 
   * @param kind the context kind; if null, {@link ContextKind#DEFAULT} will be used
   * @param key the context key
   * @return an LDContext
   * @see #create(String)
   * @see #builder(ContextKind, String)
   */
  public static LDContext create(ContextKind kind, String key) {
    return createSingle(kind, key, null, null, false, null, false);
  }
  
  /**
   * Creates a multi-kind LDContext out of the specified single-kind LDContexts.
   * <p>
   * To create a single-kind Context, use {@link #create(String)}, {@link #create(ContextKind, String)},
   * or {@link #builder(String)}.
   * <p>
   * For the returned LDContext to be valid, the contexts list must not be empty, and all of its
   * elements must be valid LDContexts. Otherwise, the returned LDContext will be invalid as
   * reported by {@link #getError()}.
   * <p>
   * If only one context parameter is given, the method returns that same context.
   * <p>
   * If the nested context is multi-kind, this is exactly equivalent to adding each of the
   * individual kinds from it separately. For instance, in the following example, "multi1" and
   * "multi2" end up being exactly the same:
   * <pre><code>
   *     LDContext c1 = LDContext.create(ContextKind.of("kind1"), "key1");
   *     LDContext c2 = LDContext.create(ContextKind.of("kind2"), "key2");
   *     LDContext c3 = LDContext.create(ContextKind.of("kind3"), "key3");
   *
   *     LDContext multi1 = LDContext.createMulti(c1, c2, c3);
   *
   *     LDContext c1plus2 = LDContext.createMulti(c1, c2);
   *     LDContext multi2 = LDContext.createMulti(c1plus2, c3);
   * </code></pre>
   * 
   * @param contexts a list of contexts
   * @return an LDContext
   * @see #multiBuilder()
   */
  public static LDContext createMulti(LDContext... contexts) {
    if (contexts == null || contexts.length == 0) {
      return failed(Errors.CONTEXT_KIND_MULTI_WITH_NO_KINDS);
    }
    if (contexts.length == 1) {
      return contexts[0]; // just return a single-kind context
    }
    for (LDContext c: contexts) {
      if (c.isMultiple()) {
        ContextMultiBuilder b = multiBuilder();
        for (LDContext c1: contexts) {
          b.add(c1);
        }
        return b.build();
      }
    }
    // copy the array because the caller could've passed in an array that they will later mutate 
    LDContext[] copied = Arrays.copyOf(contexts, contexts.length);
    return createMultiInternal(copied);
  }
  
  /**
   * Converts a user to an equivalent {@link LDContext} instance.
   * <p>
   * This method is used by the SDK whenever an application passes a {@link LDUser} instance
   * to methods such as {@code identify}. The SDK operates internally on the {@link LDContext}
   * model, which is more flexible than the older LDUser model: an L User can always be converted
   * to an LDContext, but not vice versa. The {@link ContextKind} of the resulting Context is
   * {@link ContextKind#DEFAULT} ("user").
   * <p>
   * Because there is some overhead to this conversion, it is more efficient for applications to
   * construct an LDContext and pass that to the SDK, rather than an LDUser. This is also recommended
   * because the LDUser type may be removed in a future version of the SDK.
   * <p>
   * If the {@code user} parameter is null, or if the user has a null key, the method returns an
   * LDContext in an invalid state (see {@link LDContext#isValid()}).
   *
   * @param user an LDUser object
   * @return an LDContext with the same attributes as the LDUser
   *
   * @deprecated use {@link LDContext} directly instead.
   */
  @Deprecated
  public static LDContext fromUser(LDUser user) {
    if (user == null) {
      return failed(Errors.CONTEXT_FROM_NULL_USER);
    }
    String key = user.getKey();
    if (key == null) {
      if (user.isAnonymous()) {
        // In the old user model, a user was able to have a null key for the special case
        // where (in the Android SDK only) the user was anonymous and the SDK would generate a
        // key for it. There is a different mechanism for this in the new Android SDK, but we
        // will replace the null key with "" so the original context is valid.
        key = "";
      } else {
        return failed(Errors.CONTEXT_NO_KEY);
      }
    }
    AttributeMap attributes = null;
    for (UserAttribute a: UserAttribute.OPTIONAL_STRING_ATTRIBUTES) {
      if (a == UserAttribute.NAME) {
        continue;
      }
      LDValue value = user.getAttribute(a);
      if (!value.isNull()) {
        if (attributes == null) {
          attributes = new AttributeMap(); 
        }
        attributes.put(a.getName(), value);
      }
    }
    if (user.custom != null && !user.custom.isEmpty()) {
      if (attributes == null) {
        attributes = new AttributeMap(); 
      }
      for (Map.Entry<UserAttribute, LDValue> kv: user.custom.entrySet()) {
        attributes.put(kv.getKey().getName(), kv.getValue());
      }
    }
    List<AttributeRef> privateAttributes = null;
    if (user.privateAttributeNames != null && !user.privateAttributeNames.isEmpty()) {
      privateAttributes = new ArrayList<>();
      for (UserAttribute pa: user.privateAttributeNames) {
        privateAttributes.add(AttributeRef.fromLiteral(pa.getName()));
      }
    }
    return new LDContext(
        ContextKind.DEFAULT,
        null,
        key,
        key,
        user.getName(),
        attributes,
        user.isAnonymous(),
        privateAttributes
        );
  }
  
  /**
   * Creates a {@link ContextBuilder} for building an LDContext, initializing its {@code key} and setting
   * {@code kind} to {@link ContextKind#DEFAULT}.
   * <p>
   * You may use {@link ContextBuilder} methods to set additional attributes and/or change the
   * {@link ContextBuilder#kind(ContextKind)} before calling {@link ContextBuilder#build()}.
   * If you do not change any values, the defaults for the LDContext are that its {@code kind} is
   * {@link ContextKind#DEFAULT} ("user"), its {@code key} is set to the key parameter passed here,
   * {@code anonymous} is {@code false}, and it has no values for any other attributes.
   * <p>
   * This method is for building an LDContext that has only a single Kind. To define a multi-kind
   * LDContext, use {@link #multiBuilder()}.
   * <p>
   * if {@code key} is an empty string, there is no default. An LDContext must have a non-empty
   * key, so if you call {@link ContextBuilder#build()} in this state without using
   * {@link ContextBuilder#key(String)} to set the key, you will get an invalid LDContext.
   * 
   * @param key the context key
   * @return a builder
   * @see #builder(ContextKind, String)
   * @see #multiBuilder()
   * @see #create(String)
   */
  public static ContextBuilder builder(String key) {
    return builder(ContextKind.DEFAULT, key);
  }
  
  /**
   * Creates a {@link ContextBuilder} for building an LDContext, initializing its {@code key} and
   * {@code kind}.
   * <p>
   * You may use {@link ContextBuilder} methods to set additional attributes and/or change the
   * {@link ContextBuilder#kind(ContextKind)} before calling {@link ContextBuilder#build()}.
   * If you do not change any values, the defaults for the LDContext are that its {@code kind} and
   * {@code key} is set to the parameters passed here, {@code anonymous} is {@code false}, and it has
   * no values for any other attributes.
   * <p>
   * This method is for building an LDContext that has only a single Kind. To define a multi-kind
   * LDContext, use {@link #multiBuilder()}.
   * <p>
   * if {@code key} is an empty string, there is no default. An LDContext must have a non-empty
   * key, so if you call {@link ContextBuilder#build()} in this state without using
   * {@link ContextBuilder#key(String)} to set the key, you will get an invalid LDContext.
   * 
   * @param kind the context kind; if null, {@link ContextKind#DEFAULT} is used
   * @param key the context key
   * @return a builder
   * @see #builder(String)
   * @see #multiBuilder()
   * @see #create(ContextKind, String)
   */
  public static ContextBuilder builder(ContextKind kind, String key) {
    return new ContextBuilder(kind, key);
  }
  
  /**
   * Creates a builder whose properties are the same as an existing single-kind LDContext.
   * <p>
   * You may then change the builder's state in any way and call {@link ContextBuilder#build()}
   * to create a new independent LDContext.
   * 
   * @param context the context to copy from
   * @return a builder
   * @see #builder(String)
   */
  public static ContextBuilder builderFromContext(LDContext context) {
    return new ContextBuilder().copyFrom(context);
  }
  
  /**
   * Creates a {@link ContextMultiBuilder} for building a multi-kind context.
   * <p>
   * This method is for building a Context that has multiple {@link ContextKind} values,
   * each with its own nested LDContext. To define a single-kind context, use
   * {@link #builder(String)} instead.
   * 
   * @return a builder
   * @see #createMulti(LDContext...)
   */
  public static ContextMultiBuilder multiBuilder() {
    return new ContextMultiBuilder();
  }
  
  /**
   * Returns {@code true} for a valid LDContext, {@code false} for an invalid one.
   * <p>
   * A valid context is one that can be used in SDK operations. An invalid context is one that
   * is missing necessary attributes or has invalid attributes, indicating an incorrect usage
   * of the SDK API. The only ways for a context to be invalid are:
   * <ul>
   * <li> It has a disallowed value for the {@code kind} property. See {@link ContextKind}. </li>
   * <li> It is a single-kind context whose {@code key} is empty. </li>
   * <li> It is a multi-kind context that does not have any kinds. See {@link #createMulti(LDContext...)}. </li>
   * <li> It is a multi-kind context where the same kind appears more than once. </li>
   * <li> It is a multi-kind context where at least one of the nested LDContexts has an error. </li>
   * <li> It was created with {@link #fromUser(LDUser)} from a null LDUser reference, or from an
   * LDUser that had a null key. </li>
   * </ul>
   * <p>
   * In any of these cases, {@link #isValid()} will return false, and {@link #getError()}
   * will return a description of the error.
   * <p>
   * Since in normal usage it is easy for applications to be sure they are using context kinds
   * correctly, and because throwing an exception is undesirable in application code that uses
   * LaunchDarkly, the SDK stores the error state in the LDContext itself and checks for such
   * errors at the time the Context is used, such as in a flag evaluation. At that point, if
   * the context is invalid, the operation will fail in some well-defined way as described in
   * the documentation for that method, and the SDK will generally log a warning as well. But
   * in any situation where you are not sure if you have a valid LDContext, you can check
   * {@link #isValid()} or {@link #getError()}.
   * 
   * @return true if the context is valid
   * @see #getError()
   */
  public boolean isValid() {
    return error == null;
  }
  
  /**
   * Returns null for a valid LDContext, or an error message for an invalid one.
   * <p>
   * If this is null, then {@link #isValid()} is true. If it is non-null, then {@link #isValid()}
   * is false.
   * 
   * @return an error description or null
   * @see #isValid()
   */
  public String getError() {
    return error;
  }
  
  /**
   * Returns the context's {@code kind} attribute.
   * <p>
   * Every valid context has a non-empty {@link ContextKind}. For multi-kind contexts, this value
   * is {@link ContextKind#MULTI} and the kinds within the context can be inspected with
   * {@link #getIndividualContext(int)} or {@link #getIndividualContext(String)}.
   * 
   * @return the context kind
   * @see ContextBuilder#kind(ContextKind)
   */
  public ContextKind getKind() {
    return kind;
  }
  
  /**
   * Returns true if this is a multi-kind context.
   * <p>
   * If this value is true, then {@link #getKind()} is guaranteed to be
   * {@link ContextKind#MULTI}, and you can inspect the individual contexts for each kind
   * with {@link #getIndividualContext(int)} or {@link #getIndividualContext(ContextKind)}.
   * <p>
   * If this value is false, then {@link #getKind()} is guaranteed to return a value that
   * is not {@link ContextKind#MULTI}.
   * 
   * @return true for a multi-kind context, false for a single-kind context
   */
  public boolean isMultiple() {
    return multiContexts != null;
  }

  /**
   * Returns the context's {@code key} attribute.
   * <p>
   * For a single-kind context, this value is set by one of the LDContext factory methods
   * or builders ({@link #create(String)}, {@link #create(ContextKind, String)},
   * {@link #builder(String)}, {@link #builder(ContextKind, String)}).
   * <p>
   * For a multi-kind context, there is no single value and {@link #getKey()} returns an
   * empty string. Use {@link #getIndividualContext(int)} or {@link #getIndividualContext(String)}
   * to inspect the LDContext for a particular kind, then call {@link #getKey()} on it.
   * <p>
   * This value is never null.
   * 
   * @return the context key
   * @see ContextBuilder#key(String)
   */
  public String getKey() {
    return key;
  }
  
  /**
   * Returns the context's {@code name} attribute.
   * <p>
   * For a single-kind context, this value is set by {@link ContextBuilder#name(String)}.
   * It is null if no value was set.
   * <p>
   * For a multi-kind context, there is no single value and {@link #getName()} returns null.
   * Use {@link #getIndividualContext(int)} or {@link #getIndividualContext(String)} to
   * inspect the LDContext for a particular kind, then call {@link #getName()} on it.
   * 
   * @return the context name or null
   * @see ContextBuilder#name(String)
   */
  public String getName() {
    return name;
  }

  /**
   * Returns true if this context is only intended for flag evaluations and will not be
   * indexed by LaunchDarkly.
   * <p>
   * The default value is false. False means that this LDContext represents an entity
   * such as a user that you want to be able to see on the LaunchDarkly dashboard.
   * <p>
   * Setting {@code anonymous} to true excludes this context from the database that is
   * used by the dashboard. It does not exclude it from analytics event data, so it is
   * not the same as making attributes private; all non-private attributes will still be
   * included in events and data export. There is no limitation on what other attributes
   * may be included (so, for instance, {@code anonymous} does not mean there is no
   * {@code name}), and the context will still have whatever {@code key} you have given it.
   * <p>
   * This value is also addressable in evaluations as the attribute name "anonymous". It
   * is always treated as a boolean true or false in evaluations.
   * 
   * @return true if the context should be excluded from the LaunchDarkly database
   * @see ContextBuilder#anonymous(boolean)
   */
  public boolean isAnonymous() {
    return anonymous;
  }

  /**
   * Looks up the value of any attribute of the context by name.
   * <p>
   * This includes only attributes that are addressable in evaluations-- not metadata such
   * as {@link #getPrivateAttribute(int)}.
   * <p>
   * For a single-kind context, the attribute name can be any custom attribute that was set
   * by methods like {@link ContextBuilder#set(String, boolean)}. It can also be one of the
   * built-in ones like "kind", "key", or "name"; in such cases, it is equivalent to
   * {@link #getKind()}, {@link #getKey()}, or {@link #getName()}, except that the value is
   * returned using the general-purpose {@link LDValue} type.
   * <p>
   * For a multi-kind context, the only supported attribute name is "kind". Use
   * {@link #getIndividualContext(int)} or {@link #getIndividualContext(ContextKind)} to
   * inspect the LDContext for a particular kind and then get its attributes.
   * <p>
   * This method does not support complex expressions for getting individual values out of
   * JSON objects or arrays, such as "/address/street". Use {@link #getValue(AttributeRef)}
   * with an {@link AttributeRef} for that purpose.
   * <p>
   * If the value is found, the return value is the attribute value, using the type
   * {@link LDValue} to represent a value of any JSON type.
   * <p>
   * If there is no such attribute, the return value is {@link LDValue#ofNull()} (the method
   * never returns a Java {@code null}). An attribute that actually exists cannot have a null
   * value.
   * 
   * @param attributeName the desired attribute name
   * @return the value or {@link LDValue#ofNull()}
   * @see #getValue(AttributeRef)
   * @see ContextBuilder#set(String, String)
   */
  public LDValue getValue(String attributeName) {
    return getTopLevelAttribute(attributeName);
  }
  
  /**
   * Looks up the value of any attribute of the context, or a value contained within an
   * attribute, based on an {@link AttributeRef}.
   * <p>
   * This includes only attributes that are addressable in evaluations-- not metadata such
   * as {@link #getPrivateAttribute(int)}.
   * <p>
   * This implements the same behavior that the SDK uses to resolve attribute references
   * during a flag evaluation. In a single-kind context, the {@link AttributeRef} can
   * represent a simple attribute name-- either a built-in one like "name" or "key", or a
   * custom attribute that was set by methods like {@link ContextBuilder#set(String, String)}--
   * or, it can be a slash-delimited path using a JSON-Pointer-like syntax. See
   * {@link AttributeRef} for more details.
   * <p>
   * For a multi-kind context, the only supported attribute name is "kind". Use
   * {@link #getIndividualContext(int)} or {@link #getIndividualContext(ContextKind)} to
   * inspect the LDContext for a particular kind and then get its attributes.
   * <p>
   * This method does not support complex expressions for getting individual values out of
   * JSON objects or arrays, such as "/address/street". Use {@link #getValue(AttributeRef)}
   * with an {@link AttributeRef} for that purpose.
   * <p>
   * If the value is found, the return value is the attribute value, using the type
   * {@link LDValue} to represent a value of any JSON type.
   * <p>
   * If there is no such attribute, the return value is {@link LDValue#ofNull()} (the method
   * never returns a Java {@code null}). An attribute that actually exists cannot have a null
   * value.
   * @param attributeRef an attribute reference
   * @return the attribute value
   */
  public LDValue getValue(AttributeRef attributeRef) {
    if (attributeRef == null || !attributeRef.isValid()) {
      return LDValue.ofNull();
    }
    
    String name = attributeRef.getComponent(0);
    
    if (isMultiple()) {
      if (attributeRef.getDepth() == 1 && name.equals("kind")) {
        return LDValue.of(kind.toString());
      }
      return LDValue.ofNull(); // multi-kind context has no other addressable attributes
    }
    
    // Look up attribute in single-kind context
    LDValue value = getTopLevelAttribute(name);
    if (value.isNull()) {
      return value;
    }
    for (int i = 1; i < attributeRef.getDepth(); i++) {
      String component = attributeRef.getComponent(i);
      value = value.get(component); // returns LDValue.null() if either property isn't found or value isn't an object
      if (value.isNull()) {
        break;
      }
    }
    return value;
  }
  
  /**
   * Returns the names of all non-built-in attributes that have been set in this context.
   * <p>
   * For a single-kind context, this includes all the names that were passed to
   * any of the overloads of {@link ContextBuilder#set(String, LDValue)} as long as the
   * values were not null (since a null value in LaunchDarkly is equivalent to the attribute
   * not being set).
   * <p>
   * For a multi-kind context, there are no such names.
   *    
   * @return an iterable of strings (may be empty, but will never be null)
   */
  public Iterable<String> getCustomAttributeNames() {
    return attributes == null ? Collections.<String>emptyList() : attributes.flatten().keySet();
  }
  
  /**
   * Returns the number of context kinds in this context.
   * <p>
   * For a valid single-kind context, this returns 1. For a multi-kind context, it returns
   * the number of kinds that were added with {@link #createMulti(LDContext...)} or
   * {@link #multiBuilder()}. For an invalid context, it returns zero.
   *
   * @return the number of context kinds
   */
  public int getIndividualContextCount() {
    if (error != null) {
      return 0;
    }
    return multiContexts == null ? 1 : multiContexts.length;
  }
  
  /**
   * Returns the single-kind LDContext corresponding to one of the kinds in this context.
   * <p>
   * If this method is called on a single-kind LDContext, then the only allowable value
   * for {@code index} is zero, and the return value on success is the same LDContext. If
   * the method is called on a multi-kind context, then index must be non-negative and
   * less than the number of kinds (that is, less than the return value of
   * {@link #getIndividualContextCount()}), and the return value on success is one of the
   * individual LDContexts within.
   * 
   * @param index the zero-based index of the context to get
   * @return an {@link LDContext}, or null if the index was out of range
   */
  public LDContext getIndividualContext(int index) {
    if (multiContexts == null) {
      return index == 0 ? this : null;
    }
    return index < 0 || index >= multiContexts.length ? null : multiContexts[index];
  }

  /**
   * Returns the single-kind LDContext corresponding to one of the kinds in this context.
   * <p>
   * If this method is called on a single-kind LDContext, then the only allowable value
   * for {@code kind} is the same as {@link #getKind()}, and the return value on success
   * is the same LDContext. If the method is called on a multi-kind context, then
   * {@code kind} should be match the kind of one of the contexts that was added with
   * {@link #createMulti(LDContext...)} or {@link #multiBuilder()}, and the return value on
   * success is the corresponding individual LDContext within.
   * 
   * @param kind the context kind to get; if null, defaults to {@link ContextKind#DEFAULT}
   * @return an {@link LDContext}, or null if that kind was not found
   */
  public LDContext getIndividualContext(ContextKind kind) {
    if (kind == null) {
      kind = ContextKind.DEFAULT;
    }
    if (multiContexts == null) {
      // kind.equals since kind has already been sanitized
      return kind.equals(this.kind) ? this : null;
    }
    for (LDContext c: multiContexts) {
      // kind.equals since kind has already been sanitized
      if (kind.equals(c.kind)) {
        return c;
      }
    }
    return null;
  }

  /**
   * Same as {@link #getIndividualContext(ContextKind)}, but specifies the kind as a
   * plain string.
   * 
   * @param kind the context kind to get
   * @return an {@link LDContext}, or null if that kind was not found
   */
  public LDContext getIndividualContext(String kind) {
    if (kind == null || kind.isEmpty()) {
      return getIndividualContext(ContextKind.DEFAULT);
    }
    if (multiContexts == null) {
      // kind.equals since kind has already been sanitized
      return kind.equals(this.kind.toString()) ? this : null;
    }
    for (LDContext c: multiContexts) {
      // kind.equals since kind has already been sanitized
      if (kind.equals(c.kind.toString())) {
        return c;
      }
    }
    return null;
  }
  
  /**
   * Returns the number of private attribute references that were specified for this context.
   * <p>
   * This is equal to the total number of values passed to {@link ContextBuilder#privateAttributes(String...)}
   * and/or its overload {@link ContextBuilder#privateAttributes(AttributeRef...)}.
   * 
   * @return the number of private attribute references
   */
  public int getPrivateAttributeCount() {
    return privateAttributes == null ? 0 : privateAttributes.size();
  }
  
  /**
   * Retrieves one of the private attribute references that were specified for this context.
   * 
   * @param index a non-negative index that must be less than {@link #getPrivateAttributeCount()}
   * @return an {@link AttributeRef}, or null if the index was out of range
   */
  public AttributeRef getPrivateAttribute(int index) {
    if (privateAttributes == null) {
      return null;
    }
    return index < 0 || index >= privateAttributes.size() ? null : privateAttributes.get(index);
  }
  
  /**
   * Returns a string that describes the LDContext uniquely based on {@code kind} and
   * {@code key} values.
   * <p>
   * This value is used whenever LaunchDarkly needs a string identifier based on all of the
   * {@code kind} and {@code key} values in the context; the SDK may use this for caching
   * previously seen contexts, for instance.
   * 
   * @return the fully-qualified key
   */
  public String getFullyQualifiedKey() {
    return fullyQualifiedKey;
  }
  
  /**
   * Returns a string representation of the context.
   * <p>
   * For a valid context, this is currently defined as being the same as the JSON representation,
   * since that is the simplest way to represent all of the LDContext properties. However,
   * application code should not rely on {@link #toString()} always being the same as the JSON
   * representation. If you specifically want the latter, use {@link JsonSerialization#serialize(JsonSerializable)}.
   * <p>
   * For an invalid context, {@link #toString()} returns a description of why it is invalid.
   */
  @Override
  public String toString() {
    if (!isValid()) {
      return ("(invalid LDContext: " + getError() + ")");
    }
    return JsonSerialization.serialize(this);
  }
  
  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof LDContext)) {
      return false;
    }
    LDContext o = (LDContext)other;
    if (!Objects.equals(error, o.error)) {
      return false;
    }
    if (error != null) {
      return true; // there aren't any other attributes
    }
    if (!Objects.equals(kind, o.kind)) {
      return false;
    }
    if (isMultiple()) {
      if (multiContexts.length != o.multiContexts.length) {
        return false;
      }
      for (int i = 0; i < multiContexts.length; i++) {
        if (!Objects.equals(multiContexts[i], o.multiContexts[i])) {
          return false;
        }
      }
      return true;
    }
    if (!Objects.equals(key, o.key) || !Objects.equals(name, o.name) || anonymous != o.anonymous) {
      return false;
    }
    if (!Objects.equals(attributes, o.attributes)) {
      return false;
    }
    if (getPrivateAttributeCount() != o.getPrivateAttributeCount()) {
      return false;
    }
    if (privateAttributes != null) {
      for (AttributeRef a: privateAttributes) {
        boolean found = false;
        for (AttributeRef a1: o.privateAttributes) {
          if (a1.equals(a)) {
           found = true;
           break;
          }
        }
        if (!found) {
          return false;
        }
      }
    }
    return true;
  }
  
  @Override
  public int hashCode() {
    // This implementation of hashCode() is inefficient due to the need to flatten the attributes map.
    // However, using an LDContext as a map key is not an anticipated or recommended use case.
    int h = Objects.hash(error, kind, key, name, anonymous);
    if (multiContexts != null) {
      for (LDContext c: multiContexts) {
        h = h * 17 + c.hashCode();
      }
    }
    if (attributes != null) {
      h = h * 17 + attributes.hashCode();
    }
    if (privateAttributes != null) {
      AttributeRef[] refs = privateAttributes.toArray(new AttributeRef[privateAttributes.size()]);
      Arrays.sort(refs);;
      for (AttributeRef a: refs) {
        h = h * 17 + a.hashCode();
      }
    }
    return h;
  }
  
  private LDValue getTopLevelAttribute(String attributeName) {
    switch (attributeName) {
    case "kind":
      return LDValue.of(kind.toString());
    case "key":
      return multiContexts == null ? LDValue.of(key) : LDValue.ofNull();
    case "name":
      return LDValue.of(name);
    case "anonymous":
      return LDValue.of(anonymous);
    default:
      if (attributes == null) {
        return LDValue.ofNull();
      }
      LDValue v = attributes.get(attributeName);
      return v == null ? LDValue.ofNull() : v;
    }
  }
  
  private static String escapeKeyForFullyQualifiedKey(String key) {
    // When building a FullyQualifiedKey, ':' and '%' are percent-escaped; we do not use a full
    // URL-encoding function because implementations of this are inconsistent across platforms.
    return key.replace("%", "%25").replace(":", "%3A");
  }
  
  private static class ByKindComparator implements Comparator<LDContext> {
    static final ByKindComparator INSTANCE = new ByKindComparator();
    
    public int compare(LDContext c1, LDContext c2) {
      return c1.getKind().compareTo(c2.getKind());
    }
  }
}
