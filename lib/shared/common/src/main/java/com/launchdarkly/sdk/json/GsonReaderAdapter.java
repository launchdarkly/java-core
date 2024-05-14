package com.launchdarkly.sdk.json;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.CharArrayReader;
import java.io.IOException;
import java.io.Reader;

// This type is a bridge between the Gson classes on the application classpath and the Gson classes
// that are used internally.
//
// In some SDK distributions, there is an internal set of Gson classes that have modified (shaded)
// class names, to ensure that the SDK can use its own version of Gson without conflicting with the
// application. If so, all references to Gson classes in the SDK code will be transformed to the
// shaded class names *except* within the LDGson class. This means that our Gson TypeAdapters can't
// interact directly with a JsonReader or JsonWriter that is provided by the application.
//
// GsonReaderAdapter and GsonWriterAdapter, since they are declared outside of the LDGson class,
// *will* have all Gson types in their class/method signatures shaded if we are using shading.
// Therefore, they can be used with our internal Gson logic. But the actual implementation of their
// methods is done by a subclass that is an inner class of LDGson-- so, that class can interact
// with unshaded Gson classes provided by the application.
//
// So, if all com.google.gson classes are being shaded to com.launchdarkly.shaded.com.google.gson,
// then the base class of GsonReaderAdapter is com.launchdarkly.shaded.com.google.gson.JsonReader;
// the class LDGson.DelegatingJsonReaderAdapter is a GsonReaderAdapter, so it is also a
// com.launchdarkly.shaded.com.google.gson.JsonReader; but references to JsonReader within the
// implementation of LDGson.DelegatingJsonReaderAdapter are to com.google.json.JsonReader.
//
// In SDK distributions that do not use shading, these types are not really necessary, but their
// overhead is minimal so we use them in all cases.
abstract class GsonReaderAdapter extends JsonReader {
  private static final JsonToken[] TOKEN_VALUES = JsonToken.values();
  
  GsonReaderAdapter() {
    super(makeStubReader());
  }

  private static final Reader makeStubReader() {
    // The JsonReader constructor requires a non-null Reader, but we won't actually be using it.
    // Unfortunately Java 7 doesn't implement a completely no-op Reader. 
    return new CharArrayReader(new char[0]);
  }
  
  @Override
  abstract public void beginArray() throws IOException;
  
  @Override
  abstract public void beginObject() throws IOException;
  
  @Override
  abstract public void endArray() throws IOException;
  
  @Override
  abstract public void endObject() throws IOException;
  
  @Override
  abstract public boolean hasNext() throws IOException;
  
  @Override
  abstract public boolean nextBoolean() throws IOException;
  
  @Override
  abstract public double nextDouble() throws IOException;
  
  @Override
  abstract public int nextInt() throws IOException;
  
  @Override
  abstract public long nextLong() throws IOException;
  
  @Override
  abstract public String nextName() throws IOException;
  
  @Override
  abstract public void nextNull() throws IOException;
  
  @Override
  abstract public String nextString() throws IOException;
  
  @Override
  public JsonToken peek() throws IOException {
    return TOKEN_VALUES[peekInternal()];
  }
  
  @Override
  abstract public void skipValue() throws IOException;
  
  abstract protected int peekInternal() throws IOException; // should return the ordinal of the JsonToken enum
}
