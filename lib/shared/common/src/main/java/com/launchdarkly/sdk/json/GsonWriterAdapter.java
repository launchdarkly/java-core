package com.launchdarkly.sdk.json;

import com.google.gson.stream.JsonWriter;

import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.Writer;

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
// then the base class of GsonWriterAdapter is com.launchdarkly.shaded.com.google.gson.JsonWriter;
// the class LDGson.DelegatingJsonWriterAdapter is a GsonWriterAdapter, so it is also a
// com.launchdarkly.shaded.com.google.gson.JsonWriter; but references to JsonWriter within the
// implementation of LDGson.DelegatingJsonWriterAdapter are to com.google.json.JsonWriter.
//
// In SDK distributions that do not use shading, these types are not really necessary, but their
// overhead is minimal so we use them in all cases.
abstract class GsonWriterAdapter extends JsonWriter {
  GsonWriterAdapter() {
    super(makeStubWriter());
  }
  
  private static final Writer makeStubWriter() {
    // The JsonWriter constructor requires a non-null Writer, but we won't actually be using it.
    // Unfortunately Java 7 doesn't implement a completely no-op Writer. 
    return new CharArrayWriter(0);
  }
  
  @Override
  public JsonWriter beginArray() throws IOException {
    beginArrayInternal();
    return this;
  }
  
  @Override
  public JsonWriter beginObject() throws IOException {
    beginObjectInternal();
    return this;
  }
  
  @Override
  public JsonWriter endArray() throws IOException {
    endArrayInternal();
    return this;
  }
  
  @Override
  public JsonWriter endObject() throws IOException {
    endObjectInternal();
    return this;
  }
  
  @Override
  public JsonWriter jsonValue(String value) throws IOException {
    jsonValueInternal(value);
    return this;
  }
  
  @Override
  public JsonWriter name(String name) throws IOException {
    nameInternal(name);
    return this;
  }
  
  @Override
  public JsonWriter nullValue() throws IOException {
    valueInternalNull();
    return this;
  }

  @Override
  public JsonWriter value(boolean value) throws IOException {
    valueInternalBool(value);
    return this;
  }
  
  @Override
  public JsonWriter value(Boolean value) throws IOException {
    if (value == null) {
      valueInternalNull();
    } else {
      valueInternalBool(value.booleanValue());
    }
    return this;
  }
  
  @Override
  public JsonWriter value(double value) throws IOException {
    // The following logic avoids inconsistent output by not letting the underlying framework
    // decide to append .0 to integer values
    long asLong = (long)value;
    if (value == (double)asLong) {
      valueInternalLong(asLong);
    } else {
      valueInternalDouble(value);
    }
    return this;
  }

  @Override
  public JsonWriter value(long value) throws IOException {
    valueInternalLong(value);
    return this;
  }

  @Override
  public JsonWriter value(Number value) throws IOException {
    if (value == null) {
      valueInternalNull();
    } else {
      value(value.doubleValue());
    }
    return this;
  }
  
  @Override
  public JsonWriter value(String value) throws IOException {
    valueInternalString(value);
    return this;
  }
  
  @Override
  public void close() throws IOException {}
  
  protected abstract void beginArrayInternal() throws IOException;
  protected abstract void beginObjectInternal() throws IOException;
  protected abstract void endArrayInternal() throws IOException;
  protected abstract void endObjectInternal() throws IOException;
  protected abstract void jsonValueInternal(String value) throws IOException;
  protected abstract void nameInternal(String name) throws IOException;
  protected abstract void valueInternalNull() throws IOException;
  protected abstract void valueInternalBool(boolean value) throws IOException;
  protected abstract void valueInternalDouble(double value) throws IOException;
  protected abstract void valueInternalLong(long value) throws IOException;
  protected abstract void valueInternalString(String value) throws IOException;
}
