package com.launchdarkly.sdk;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

final class AttributeRefTypeAdapter extends TypeAdapter<AttributeRef> {
  @Override
  public AttributeRef read(JsonReader reader) throws IOException {
    return AttributeRef.fromPath(Helpers.readNonNullableString(reader));
  }

  @Override
  public void write(JsonWriter writer, AttributeRef a) throws IOException {
    writer.value(a.toString());
  }
}
