package com.launchdarkly.sdk;

import java.util.ArrayList;
import java.util.List;

/**
 * A mutable object that uses the builder pattern to specify properties for {@link LDContext}.
 * <p>
 * Use this type if you need to construct a context that has only a single kind. To define a
 * multi-kind context, use {@link LDContext#createMulti(LDContext...)} or
 * {@link LDContext#multiBuilder()}.
 * <p>
 * Obtain an instance of ContextBuilder by calling {@link LDContext#builder(String)} or
 * {@link LDContext#builder(ContextKind, String)}. Then, call setter methods such as
 * {@link #name(String)} or {@link #set(String, String)} to specify any additional attributes.
 * Then, call {@link #build()} to create the context. ContextBuilder setters return a reference
 * to the same builder, so calls can be
 * chained:
 * <pre><code>
 *     LDContext context = LDContext.builder("context-key-123abc")
 *       .name("my-name)
 *       .set("country", "us")
 *       .build();
 * </code></pre>
 * <p>
 * A ContextBuilder should not be accessed by multiple threads at once. Once you have called
 * {@link #build()}, the resulting LDContext is immutable and is safe to use from multiple
 * threads. Instances created with {@link #build()} are not affected by subsequent actions
 * taken on the builder.
 */
public final class ContextBuilder {
  private ContextKind kind;
  private String key;
  private String name;
  private Attributes attributes;
  private boolean anonymous;
  private List<AttributeRef> privateAttributes;
  private boolean copyOnWriteAttributes;
  private boolean copyOnWritePrivateAttributes;
  private boolean allowEmptyKey;
  
  ContextBuilder() {}
  
  ContextBuilder(ContextKind kind, String key) {
    this.kind = kind;
    this.key = key;
  }
  
  /**
   * Creates an {@link LDContext} from the current builder properties.
   * <p>
   * The LDContext is immutable and will not be affected by any subsequent actions on the
   * ContextBuilder.
   * <p>
   * It is possible to specify invalid attributes for a ContextBuilder, such as an empty key.
   * Instead of throwing an exception, the ContextBuilder always returns an LDContext and
   * you can check {@link LDContext#isValid()} or {@link LDContext#getError()} to see if it
   * has an error. See {@link LDContext#isValid()} for more information about invalid
   * conditions. If you pass an invalid LDContext to an SDK method, the SDK will detect this
   * and will log a description of the error.
   * 
   * @return a new {@link LDContext}
   */
  public LDContext build() {
    this.copyOnWriteAttributes = attributes != null;
    this.copyOnWritePrivateAttributes = privateAttributes != null;
    
    return LDContext.createSingle(kind, key, name, attributes, anonymous, privateAttributes, allowEmptyKey);
  }
  
  /**
   * Sets the context's kind attribute.
   * <p>
   * Every LDContext has a kind. Setting it to an empty string or null is equivalent to
   * {@link ContextKind#DEFAULT} ("user"). This value is case-sensitive. For validation
   * rules, see {@link ContextKind}.
   * 
   * @param kind the context kind
   * @return the builder
   * @see LDContext#getKind()
   */
  public ContextBuilder kind(ContextKind kind) {
    this.kind = kind;
    return this;
  }

  /**
   * Sets the context's kind attribute, as a string.
   * <p>
   * This method is a shortcut for calling {@code kind(ContextKind.of(kindName))}, since the
   * method name already prevents ambiguity about the intended type
   * 
   * @param kindString the context kind
   * @return the builder
   * @see LDContext#getKind()
   */
  public ContextBuilder kind(String kindString) {
    return kind(ContextKind.of(kindString));
  }
  
  /**
   * Sets the context's key attribute.
   * <p>
   * Every Context has a key, which is always a string. It cannot be an empty string, but
   * there are no other restrictions on its value.
   * <p>
   * The key attribute can be referenced by flag rules, flag target lists, and segments.
   * 
   * @param key the context key
   * @return the builder
   * @see LDContext#getKey()
   */
  public ContextBuilder key(String key) {
    this.key = key;
    return this;
  }
  
  /**
   * Sets the context's name attribute.
   * <p>
   * This attribute is optional. It has the following special rules:
   * <ul>
   * <li> Unlike most other attributes, it is always a string if it is specified. </li>
   * <li> The LaunchDarkly dashboard treats this attribute as the preferred display name
   * for contexts. </li>
   * </ul>
   * 
   * @param name the name attribute (null to unset the attribute)
   * @return the builder
   * @see LDContext#getName()
   */
  public ContextBuilder name(String name) {
    this.name = name;
    return this;
  }

  /**
   * Sets whether the context is only intended for flag evaluations and should not be
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
   * @param anonymous true if the context should be excluded from the LaunchDarkly database
   * @return the builder
   * @see LDContext#isAnonymous()
   */
  public ContextBuilder anonymous(boolean anonymous) {
    this.anonymous = anonymous;
    return this;
  }
 
  /**
   * Sets the value of any attribute for the context.
   * <p>
   * This includes only attributes that are addressable in evaluations-- not metadata
   * such as {@link #privateAttributes(String...)}. If {@code attributeName} is
   * "privateAttributes", you will be setting an attribute with that name which you can
   * use in evaluations or to record data for your own purposes, but it will be unrelated
   * to {@link #privateAttributes(String...)}.
   * <p>
   * This method uses the {@link LDValue} type to represent a value of any JSON type:
   * null, boolean, number, string, array, or object. For all attribute names that do
   * not have special meaning to LaunchDarkly, you may use any of those types. Values of
   * different JSON types are always treated as different values: for instance, null,
   * false, and the empty string "" are not the the same, and the number 1 is not the
   * same as the string "1".
   * <p>
   * The following attribute names have special restrictions on their value types, and
   * any value of an unsupported type will be ignored (leaving the attribute unchanged):
   * <ul>
   * <li> "kind", "key": Must be a string. See {@link #kind(ContextKind)} and
   * {@link #key(String)}. </li>
   * <li> "name": Must be a string or null. See {@link #name(String)}. </li>
   * <li> "anonymous": Must be a boolean. See {@link #anonymous(boolean)}. </li>
   * </ul>
   * <p>
   * The attribute name "_meta" is not allowed, because it has special meaning in the
   * JSON schema for contexts; any attempt to set an attribute with this name has no
   * effect. Also, any attempt to set an attribute with an empty or null name has no effect.
   * <p>
   * Values that are JSON arrays or objects have special behavior when referenced in
   * flag/segment rules.
   * <p>
   * A value of {@code null} or {@link LDValue#ofNull()} is equivalent to removing any
   * current non-default value of the attribute. Null is not a valid attribute value in
   * the LaunchDarkly model; any expressions in feature flags that reference an attribute
   * with a null value will behave as if the attribute did not exist.
   * 
   * @param attributeName the attribute name to set
   * @param value the value to set
   * @return the builder
   * @see #set(String, boolean)
   * @see #set(String, int)
   * @see #set(String, double)
   * @see #set(String, String)
   * @see #trySet(String, LDValue)
   */
  public ContextBuilder set(String attributeName, LDValue value) {
    trySet(attributeName, value);
    return this;
  }

  /**
   * Same as {@link #set(String, LDValue)} for a boolean value.
   * 
   * @param attributeName the attribute name to set
   * @param value the value to set
   * @return the builder
   * @see #set(String, LDValue)
   */
  public ContextBuilder set(String attributeName, boolean value) {
    return set(attributeName, LDValue.of(value));
  }

  /**
   * Same as {@link #set(String, LDValue)} for an integer numeric value.
   * 
   * @param attributeName the attribute name to set
   * @param value the value to set
   * @return the builder
   * @see #set(String, LDValue)
   */
  public ContextBuilder set(String attributeName, int value) {
    return set(attributeName, LDValue.of(value));
  }

  /**
   * Same as {@link #set(String, LDValue)} for a double-precision numeric value.
   * 
   * @param attributeName the attribute name to set
   * @param value the value to set
   * @return the builder
   * @see #set(String, LDValue)
   */
  public ContextBuilder set(String attributeName, double value) {
    return set(attributeName, LDValue.of(value));
  }

  /**
   * Same as {@link #set(String, LDValue)} for a string value.
   * 
   * @param attributeName the attribute name to set
   * @param value the value to set
   * @return the builder
   * @see #set(String, LDValue)
   */
  public ContextBuilder set(String attributeName, String value) {
    return set(attributeName, LDValue.of(value));
  }
  
  /**
   * Same as {@link #set(String, LDValue)}, but returns a boolean indicating whether
   * the attribute was successfully set.
   * 
   * @param attributeName the attribute name to set
   * @param value the value to set
   * @return true if successful; false if the name was invalid or the value was not
   *   an allowed type for that attribute
   */
  public boolean trySet(String attributeName, LDValue value) {
    if (attributeName == null || attributeName.isEmpty()) {
      return false;
    }
    switch (attributeName) {
    case "kind":
      if (!value.isString()) {
        return false;
      }
      kind = ContextKind.of(value.stringValue());
      break;
    case "key":
      if (!value.isString()) {
        return false;
      }
      key = value.stringValue();
      break;
    case "name":
      if (!value.isString() && !value.isNull()) {
        return false;
      }
      name = value.stringValue();
      break;
    case "anonymous":
      if (value.getType() != LDValueType.BOOLEAN) {
        return false;
      }
      anonymous = value.booleanValue();
      break;
    case "_meta":
      return false;
    default:
      if (copyOnWriteAttributes) {
        attributes = new Attributes.OfMap(attributes);
        copyOnWriteAttributes = false;
      }
      if (value == null || value.isNull()) {
        if (attributes != null) {
          attributes = attributes.remove(attributeName);
        }
      } else {
        if (attributes == null) {
          attributes = new Attributes.OfMap();
        }
        attributes = attributes.put(attributeName, value);
      }
    }
    return true;
  }

  /**
   * Dynamically (and lazily) get attribute values from a provider.
   * Any existing attributes previously accumulated on this builder
   * will be used if the provider does not provide a value.
   * @param attributeProvider the provider
   * @return the builder
   */
  public ContextBuilder attributes(AttributeProvider attributeProvider) {
    attributes = new Attributes.OfProvider(attributes, attributeProvider);
    return this;
  } 

  /**
   * Designates any number of context attributes, or properties within them, as private:
   * that is, their values will not be recorded by LaunchDarkly.
   * <p>
   * Each parameter can be either a simple attribute name (like "email"), or an attribute
   * reference in the syntax described for {@link AttributeRef} (like "/address/street").
   * 
   * @param attributeRefs attribute references to mark as private
   * @return the builder
   */
  public ContextBuilder privateAttributes(String... attributeRefs) {
    if (attributeRefs == null || attributeRefs.length == 0) {
      return this;
    }
    prepareToChangePrivate();
    for (String a: attributeRefs) {
      privateAttributes.add(AttributeRef.fromPath(a));
    }
    return this;
  }
  
  /**
   * Equivalent to {@link #privateAttributes(String...)}, but uses the {@link AttributeRef}
   * type.
   * <p>
   * Application code is unlikely to need to use the {@link AttributeRef} type directly;
   * however, in cases where you are constructing LDContexts constructed repeatedly with
   * the same set of private attributes, if you are also using complex private attribute
   * path references such as "/address/street", converting this to an AttributeRef once
   * and reusing it in many {@code privateAttribute} calls is slightly more efficient
   * than passing a string (since it does not need to parse the path repeatedly).
   * 
   * @param attributeRefs attribute references to mark as private
   * @return the builder
   */
  public ContextBuilder privateAttributes(AttributeRef... attributeRefs) {
    if (attributeRefs == null || attributeRefs.length == 0) {
      return this;
    }
    prepareToChangePrivate();
    for (AttributeRef a: attributeRefs) {
      privateAttributes.add(a);
    }
    return this;
  }
  
  // Deliberately not public - this is how we make it possible to deserialize an old-style user
  // from JSON where the key is an empty string, because that was allowed in the old schema,
  // whereas in all other cases a context key must not be an empty string.
  void setAllowEmptyKey(boolean allowEmptyKey) {
    this.allowEmptyKey = allowEmptyKey;
  }
  
  ContextBuilder copyFrom(LDContext context) {
    kind = context.getKind();
    key = context.getKey();
    name = context.getName();
    anonymous = context.isAnonymous();
    attributes = context.attributes;
    privateAttributes = context.privateAttributes;
    copyOnWriteAttributes = context.attributes != null;
    copyOnWritePrivateAttributes = context.privateAttributes != null;
    return this;
  }
  
  private void prepareToChangePrivate() {
    if (copyOnWritePrivateAttributes) {
      privateAttributes = new ArrayList<>(privateAttributes);
      copyOnWritePrivateAttributes = false;
    } else if (privateAttributes == null) {
      privateAttributes = new ArrayList<>();
    }
  }
}
