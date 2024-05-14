package com.launchdarkly.sdk;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static java.util.Collections.unmodifiableList;

@JsonAdapter(LDValueTypeAdapter.class)
final class LDValueArray extends LDValue {
  private static final LDValueArray EMPTY = new LDValueArray(Collections.<LDValue>emptyList());
  private final List<LDValue> list;

  static LDValueArray fromList(List<LDValue> list) {
    return list == null || list.isEmpty() ? EMPTY : new LDValueArray(list);
  }

  private LDValueArray(List<LDValue> list) {
    this.list = unmodifiableList(list);
  }
  
  public LDValueType getType() {
    return LDValueType.ARRAY;
  }
  
  @Override
  public int size() {
    return list.size();
  }
  
  @Override
  public Iterable<LDValue> values() {
    return list;
  }
  
  @Override
  public LDValue get(int index) {
    if (index >= 0 && index < list.size()) {
      return list.get(index);
    }
    return ofNull();
  }

  @Override
  void write(JsonWriter writer) throws IOException {
    writer.beginArray();
    for (LDValue v: list) {
      v.write(writer);
    }
    writer.endArray();
  }
}
