package com.launchdarkly.sdk;

import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.Map;

import static com.launchdarkly.sdk.LDContext.ATTR_ANONYMOUS;
import static com.launchdarkly.sdk.LDContext.ATTR_KEY;
import static com.launchdarkly.sdk.LDContext.ATTR_KIND;
import static com.launchdarkly.sdk.LDContext.ATTR_NAME;

final class LDContextTypeAdapter extends TypeAdapter<LDContext> {
  private static final String JSON_PROP_META = "_meta";
  private static final String JSON_PROP_PRIVATE = "privateAttributes";
  private static final String JSON_PROP_OLD_PRIVATE = "privateAttributeNames";
  private static final String JSON_PROP_OLD_CUSTOM = "custom";
  
  @Override
  public void write(JsonWriter out, LDContext c) throws IOException {
    if (!c.isValid()) {
      throw new JsonIOException("tried to serialize invalid LDContext: " + c.getError());
    }
    if (c.isMultiple()) {
      out.beginObject();
      out.name(ATTR_KIND).value(ContextKind.MULTI.toString());
      for (LDContext c1: c.multiContexts) {
        out.name(c1.getKind().toString());
        writeSingleKind(out, c1, false);
      }
      out.endObject();
    } else {
      writeSingleKind(out, c, true);
    }
  }
  
  private void writeSingleKind(JsonWriter out, LDContext c, boolean includeKind) throws IOException {
    out.beginObject();
    if (includeKind) {
      out.name(ATTR_KIND).value(c.getKind().toString());
    }
    out.name(ATTR_KEY).value(c.getKey());
    if (c.getName() != null) {
      out.name(ATTR_NAME).value(c.getName());
    }
    if (c.isAnonymous()) {
      out.name(ATTR_ANONYMOUS).value(c.isAnonymous());
    }
    if (c.attributes != null) {
      for (Map.Entry<String, LDValue> kv: c.attributes.entrySet()) {
        out.name(kv.getKey());
        LDValueTypeAdapter.INSTANCE.write(out, kv.getValue());
      }
    }
    if (c.getPrivateAttributeCount() != 0) {
      out.name(JSON_PROP_META).beginObject();
      out.name(JSON_PROP_PRIVATE).beginArray();
      for (AttributeRef a: c.privateAttributes) {
        out.value(a.toString());
      }
      out.endArray();
      out.endObject();
    }
    out.endObject();
  }

  @Override
  public LDContext read(JsonReader in) throws IOException {
    LDValue obj = requireValueType(LDValueTypeAdapter.INSTANCE.read(in), LDValueType.OBJECT, false, null);
    ContextKind kind = null;
    for (String key: obj.keys()) {
      if (key.equals(ATTR_KIND)) {
        kind = ContextKind.of(
            requireValueType(obj.get(key), LDValueType.STRING, false, ATTR_KIND).stringValue());
        break;
      }
    }
    LDContext ret;
    if (kind == null) {
      ret = readOldUser(obj);
    } else if (kind.equals(ContextKind.MULTI)) {
      ContextMultiBuilder mb = LDContext.multiBuilder();
      for (String key: obj.keys()) {
        if (!key.equals(ATTR_KIND)) {
          mb.add(readSingleKind(obj.get(key), ContextKind.of(key)));
        }
      }
      ret = mb.build();
    } else {
      ret = readSingleKind(obj, null);
    }
    if (!ret.isValid()) {
      throw new JsonParseException("invalid LDContext: " + ret.getError());
    }
    return ret;
  }

  private static LDValue requireValueType(LDValue v, LDValueType t, boolean nullable, String propName) throws JsonParseException {
    if (v.getType() != t && !(nullable && v.isNull())) {
      throw new JsonParseException("expected " + t + ", found " + v.getType() +
          (propName == null ? "" : (" for " + propName)));
    }
    return v;
  }
  
  private static LDContext readOldUser(LDValue obj) throws JsonParseException {
    requireValueType(obj, LDValueType.OBJECT, false, null);
    ContextBuilder cb = LDContext.builder(null);
    cb.setAllowEmptyKey(true);
    for (String key: obj.keys()) {
      LDValue v = obj.get(key);
      switch (key) {
      case ATTR_KEY:
        cb.key(requireValueType(v, LDValueType.STRING, false, key).stringValue());
        break;
      case ATTR_NAME:
        cb.name(requireValueType(v, LDValueType.STRING, true, key).stringValue());
        break;
      case ATTR_ANONYMOUS:
        cb.anonymous(requireValueType(v, LDValueType.BOOLEAN, true, key).booleanValue());
        break;
      case JSON_PROP_OLD_PRIVATE:
        LDValue privateAttrs = requireValueType(v, LDValueType.ARRAY, true, JSON_PROP_OLD_PRIVATE);
        for (LDValue privateAttr: privateAttrs.values()) {
          cb.privateAttributes(AttributeRef.fromLiteral(
              requireValueType(privateAttr, LDValueType.STRING, false, JSON_PROP_PRIVATE).stringValue()));
        }
        break;
      case JSON_PROP_OLD_CUSTOM:
        for (String customKey: requireValueType(v, LDValueType.OBJECT, true, JSON_PROP_OLD_CUSTOM).keys()) {
          cb.set(customKey, v.get(customKey));
        }
        break;
      case "firstName":
      case "lastName":
      case "email":
      case "country":
      case "ip":
      case "avatar":
        cb.set(key, requireValueType(v, LDValueType.STRING, true, key));
        break;
      default:
        break; 
      }
    }
    return cb.build();
  }
  
  private static LDContext readSingleKind(LDValue obj, ContextKind kind) throws JsonParseException {
    requireValueType(obj, LDValueType.OBJECT, false, kind == null ? null : kind.toString());
    ContextBuilder cb = LDContext.builder("").kind(kind);
    boolean hasNonEmptyKind = kind != null;
    for (String key: obj.keys()) {
      LDValue v = obj.get(key);
      switch (key) {
      case ATTR_KIND:
        String s = requireValueType(v, LDValueType.STRING, false, key).stringValue();
        if (!s.isEmpty()) {
          // We need this extra check because the builder, when used programmatically, treats an
          // unset/emty kind the same as ContextKind.DEFAULT-- but that's not the behavior we
          // want for JSON.
          hasNonEmptyKind = true;
          cb.kind(s);
        }
        break;
      case ATTR_KEY:
        cb.key(requireValueType(v, LDValueType.STRING, false, key).stringValue());
        break;
      case ATTR_NAME:
        cb.name(requireValueType(v, LDValueType.STRING, true, key).stringValue());
        break;
      case ATTR_ANONYMOUS:
        cb.anonymous(requireValueType(v, LDValueType.BOOLEAN, true, key).booleanValue());
        break;
      case JSON_PROP_META:
        LDValue meta = requireValueType(v, LDValueType.OBJECT, true, key);
        LDValue privateAttrs = requireValueType(meta.get(JSON_PROP_PRIVATE),
            LDValueType.ARRAY, true, JSON_PROP_PRIVATE);
        for (LDValue privateAttr: privateAttrs.values()) {
          cb.privateAttributes(AttributeRef.fromPath(
              requireValueType(privateAttr, LDValueType.STRING, false, JSON_PROP_PRIVATE).stringValue()));
        }
        break;
      default:
        cb.set(key, v); 
      }
    }
    if (!hasNonEmptyKind) {
      return LDContext.failed(Errors.CONTEXT_KIND_CANNOT_BE_EMPTY);
    }
    return cb.build();
  }
}
