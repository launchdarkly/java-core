package com.launchdarkly.sdk;

import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.util.Iterator;

/**
 * Internal helper classes that serve the same purpose as Guava helpers. We do not use Guava in this
 * library because the Android SDK does not have it.
 */
abstract class Helpers {
  private Helpers() {}
  
  // This implementation is much simpler than Guava's Iterables.transform() because it does not attempt
  // to support remove().
  static <T, U> Iterable<U> transform(final Iterable<T> source, final Function<T, U> fn) {
    return new Iterable<U>() {
      @Override
      public Iterator<U> iterator() {
        final Iterator<T> sourceIterator = source.iterator();
        return new Iterator<U>() {
          @Override
          public boolean hasNext() {
            return sourceIterator.hasNext();
          }

          @Override
          public U next() {
            return fn.apply(sourceIterator.next());
          }
        };
      }
    };
  }

  // Necessary because Gson's nextString() doesn't allow nulls and *does* allow non-string values
  static String readNullableString(JsonReader reader) throws IOException {
    switch (reader.peek()) {
    case STRING:
      return reader.nextString();
    case NULL:
      reader.nextNull();
      return null;
    default:
      throw new JsonParseException("expected string value or null");
    }
  }
  
  static String readNonNullableString(JsonReader reader) throws IOException {
    switch (reader.peek()) {
    case STRING:
      return reader.nextString();
    default:
      throw new JsonParseException("expected string value");
    }
  }
  
  static <T extends Enum<T>> T readEnum(Class<T> enumClass, JsonReader reader) throws IOException {
    String s = readNonNullableString(reader);
    try {
      return Enum.valueOf(enumClass, s);
    } catch (IllegalArgumentException e) {
      throw new JsonParseException(String.format("unsupported value \"%s\" for %s", s, enumClass));
    }
  }
}
