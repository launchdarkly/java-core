package com.launchdarkly.sdk;

import java.util.HashMap;
import java.util.Map;

abstract class Attributes {
  protected final Attributes parent;

  Attributes(Attributes parent) {
    this.parent = parent;
  }
  
  abstract LDValue getInternal(String key);
  
  abstract Attributes put(String key, LDValue value);
  
  abstract Attributes remove(String key);
  
  abstract Iterable<String> keys();

  LDValue get(String key) {
    Attributes current = this;
    while (current != null) {
      LDValue value = current.getInternal(key);
      if (value != null) {
        if (value.isNull()) {
          break;
        }
        return value;
      }
      current = current.parent;
    }
    return null;
  }

  @Override
  public final int hashCode() {
    return flatten().hashCode();
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Attributes)) {
      return false;
    }
    Attributes o = (Attributes) other;
    return flatten().equals(o.flatten());
  }

  Map<String, LDValue> flatten() {
    Map<String, LDValue> out = new HashMap<>();
    flattenRecursive(out);
    return out;
  }

  private final void flattenRecursive(Map<String, LDValue> out) {
    if (parent != null) {
      parent.flattenRecursive(out);
    }
    for (String key : keys()) {
      LDValue value = getInternal(key);
      if (value.isNull()) {
        out.remove(key);
      } else {
        out.put(key, value);
      }
    }
  }

  static final class OfMap extends Attributes {
    private final HashMap<String, LDValue> map;

    OfMap() {
      this(null);
    }

    OfMap(Attributes parent) {
      super(parent);
      this.map = new HashMap<>();
    }

    LDValue getInternal(String key) {
      return map.get(key);
    }

    Attributes put(String key, LDValue value) {
      map.put(key, value);
      return this;
    }

    Attributes remove(String key) {
      if (parent == null) {
        map.remove(key);
      } else{
        // we need to hide the value from the parents
        map.put(key, LDValue.ofNull());
      }
      return this;
    }

    Iterable<String> keys() {
      return map.keySet();
    }

    Map<String, LDValue> flatten() {
      // fast path, when no flattening is needed
      if (parent == null) {
        return map;
      }
      return super.flatten();
    }
  }

  static final class OfProvider extends Attributes {
    private final AttributeProvider provider;

    OfProvider(Attributes parent, AttributeProvider provider) {
      super(parent);
      this.provider = provider;
    }

    LDValue getInternal(String key) {
      return provider.getValue(key);
    }

    Attributes put(String key, LDValue value) {
      return new OfMap(this).put(key, value);
    }

    Attributes remove(String key) {
      return new OfMap(this).remove(key);
    }

    Iterable<String> keys() {
      return provider.getKeys();
    }
  }
}