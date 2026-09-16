package com.launchdarkly.sdk;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

final class AttributeMap implements Serializable {
  private final AttributeMap parent;
  private final Map<String, LDValue> map;

  AttributeMap() {
    this(null);
  }

  AttributeMap(AttributeMap parent) {
    this.parent = parent;
    this.map = new HashMap<>();
  }

  LDValue get(String key) {
    AttributeMap current = this;
    while (current != null) {
      LDValue value = current.map.get(key);
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

  void put(String key, LDValue value) {
    map.put(key, value);
  }

  @Override
  public int hashCode() {
    return flatten().hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AttributeMap)) {
      return false;
    }
    AttributeMap o = (AttributeMap) other;
    return flatten().equals(o.flatten());
  }

  Map<String, LDValue> flatten() {
    if (parent == null) {
      return map;
    }
    Map<String, LDValue> out = new HashMap<>();
    flattenRecursive(out);
    return out;
  }

  private void flattenRecursive(Map<String, LDValue> out) {
    if (parent != null) {
      parent.flattenRecursive(out);
    }
    for (Map.Entry<String, LDValue> entry : map.entrySet()) {
      String key = entry.getKey();
      LDValue value = entry.getValue();
      if (value.isNull()) {
        out.remove(key);
      } else {
        out.put(key, value);
      }
    }
  }

  void remove(String key) {
    if (parent == null) {
      map.remove(key);
      return;
    }
    // we need to hide the value from the parents
    map.put(key, LDValue.ofNull());
  }
}