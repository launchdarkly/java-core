package com.launchdarkly.sdk;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

final class EvaluationDetailTypeAdapterFactory implements TypeAdapterFactory {
  // This needs to be a TypeAdapterFactory rather than a TypeAdapter because in order to deserialize
  // an instance, we need to know what the generic type parameter for the value is.
  
  @SuppressWarnings("unchecked")
  @Override
  public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
    if (type.getType() instanceof ParameterizedType) {
      ParameterizedType pt = (ParameterizedType)type.getType();
      Type[] args = pt.getActualTypeArguments();
      if (args.length == 1) {
        return (TypeAdapter<T>)new EvaluationDetailTypeAdapter<T>(gson, args[0]);
      }
    }
    // When the generic type is unknown (EvaluationDetail<?>), we'll treat it as LDValue. 
    return (TypeAdapter<T>)new EvaluationDetailTypeAdapter<Object>(gson, LDValue.class);
  }
  
  static final class EvaluationDetailTypeAdapter<T> extends TypeAdapter<EvaluationDetail<T>> {
    private final Gson gson;
    private final Type valueType;
    
    EvaluationDetailTypeAdapter(Gson gson, Type valueType) {
      this.gson = gson;
      this.valueType = valueType;
    }
    
    @Override
    public void write(JsonWriter out, EvaluationDetail<T> value) throws IOException {
      out.beginObject();
      
      out.name("value");
      if (value.getValue() == null) {
        out.nullValue();
      } else {
        gson.toJson(value.getValue(), Object.class, out);
      }
      if (!value.isDefaultValue()) {
        out.name("variationIndex");
        out.value(value.getVariationIndex());
      }
      out.name("reason");
      gson.toJson(value.getReason(), EvaluationReason.class, out);
      
      out.endObject();
    }

    @SuppressWarnings("unchecked")
    @Override
    public EvaluationDetail<T> read(JsonReader in) throws IOException {
      T value = null;
      int variation = EvaluationDetail.NO_VARIATION;
      EvaluationReason reason = null;
      
      in.beginObject();
      
      while (in.peek() != JsonToken.END_OBJECT) {
        String key = in.nextName();
        switch (key) {
        case "value":
          value = gson.fromJson(in, valueType);
          break;
        case "variationIndex":
          variation = in.nextInt();
          break;
        case "reason":
          reason = EvaluationReasonTypeAdapter.parse(in);
          break;
        default:
          in.skipValue();
        }
      }
      in.endObject();

      if (value == null && valueType == LDValue.class) {
        value = (T)LDValue.ofNull(); // normalize to get around gson's habit of skipping the TypeAdapter for nulls 
      }
      return EvaluationDetail.fromValue(value, variation, reason);
    }
  }
}
