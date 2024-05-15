package com.launchdarkly.sdk;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

final class ContextKindTypeAdapter extends TypeAdapter<ContextKind> {
  @Override
  public ContextKind read(JsonReader reader) throws IOException {
    return ContextKind.of(Helpers.readNonNullableString(reader));
  }

  @Override
  public void write(JsonWriter writer, ContextKind k) throws IOException {
    writer.value(k.toString());
  }
}
