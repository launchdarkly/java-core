package com.launchdarkly.sdk.server.ai.internal;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.LDValueConverter;
import com.launchdarkly.sdk.LDValueType;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.JudgeConfiguration;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Message;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Mode;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Model;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Provider;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses an {@link LDValue} flag variation into the strongly-typed {@link AIConfigFlagValue}.
 * <p>
 * Parsing is intentionally defensive: malformed, missing, or wrong-typed fields never raise an
 * exception. Instead, individual fields fall back to {@code null} (or are skipped, for list/map
 * entries), so a corrupt payload degrades to a safe, disabled-looking configuration rather than
 * failing the caller's AI request. This mirrors the lenient decoding of the JS and Python SDKs.
 * <p>
 * This class is an internal implementation detail and is not part of the supported API.
 */
public final class AIConfigParser {
  private AIConfigParser() {
  }

  /**
   * Parses a flag variation value.
   *
   * @param value the raw flag value; may be {@code null} or any JSON type
   * @return the parsed representation; never {@code null} (an empty value yields an all-absent
   *     result)
   */
  public static AIConfigFlagValue parse(LDValue value) {
    AIConfigFlagValue.Builder builder = AIConfigFlagValue.builder();
    if (value == null || value.getType() != LDValueType.OBJECT) {
      return builder.build();
    }

    parseMeta(value.get("_ldMeta"), builder);
    builder.model(parseModel(value.get("model")));
    builder.provider(parseProvider(value.get("provider")));
    builder.messages(parseMessages(value.get("messages")));
    builder.instructions(asStringOrNull(value.get("instructions")));
    builder.tools(resolveTools(value));
    builder.judgeConfiguration(parseJudgeConfiguration(value.get("judgeConfiguration")));
    builder.evaluationMetricKey(resolveEvaluationMetricKey(value));

    return builder.build();
  }

  private static void parseMeta(LDValue meta, AIConfigFlagValue.Builder builder) {
    if (meta == null || meta.getType() != LDValueType.OBJECT) {
      return;
    }
    LDValue enabled = meta.get("enabled");
    if (enabled.getType() == LDValueType.BOOLEAN) {
      builder.enabled(enabled.booleanValue());
    }
    builder.variationKey(asStringOrNull(meta.get("variationKey")));
    LDValue version = meta.get("version");
    if (version.getType() == LDValueType.NUMBER) {
      builder.version(version.intValue());
    }
    builder.mode(Mode.fromWireValue(asStringOrNull(meta.get("mode"))));
  }

  /**
   * Parses a {@code model} object.
   *
   * @param model the model value
   * @return a {@link Model}, or {@code null} if the value is not an object
   */
  static Model parseModel(LDValue model) {
    if (model == null || model.getType() != LDValueType.OBJECT) {
      return null;
    }
    LDValue modelVersion = model.get("modelVersion");
    int version = modelVersion.getType() == LDValueType.NUMBER ? modelVersion.intValue() : 1;
    return Model.builder(asStringOrNull(model.get("name")))
        .modelKey(trimToNull(asStringOrNull(model.get("modelKey"))))
        .modelVersion(version)
        .parameters(LDValueConverter.toMap(model.get("parameters")))
        .custom(LDValueConverter.toMap(model.get("custom")))
        .build();
  }

  /**
   * Parses a {@code provider} object.
   *
   * @param provider the provider value
   * @return a {@link Provider}, or {@code null} if the value is not an object
   */
  static Provider parseProvider(LDValue provider) {
    if (provider == null || provider.getType() != LDValueType.OBJECT) {
      return null;
    }
    return new Provider(asStringOrNull(provider.get("name")));
  }

  /**
   * Parses a {@code messages} array. Entries missing a recognized role or a string {@code content}
   * are skipped.
   *
   * @param messages the messages value
   * @return a list of {@link Message}, or {@code null} if the value is not an array
   */
  static List<Message> parseMessages(LDValue messages) {
    if (messages == null || messages.getType() != LDValueType.ARRAY) {
      return null;
    }
    List<Message> result = new ArrayList<>(messages.size());
    for (LDValue entry : messages.values()) {
      if (entry == null || entry.getType() != LDValueType.OBJECT) {
        continue;
      }
      Message.Role role = Message.Role.fromWireValue(asStringOrNull(entry.get("role")));
      LDValue content = entry.get("content");
      if (role == null || content.getType() != LDValueType.STRING) {
        continue;
      }
      result.add(new Message(role, content.stringValue()));
    }
    return result;
  }

  /**
   * Resolves the root-level tools map. Prefers the top-level {@code tools} object; otherwise falls
   * back to deriving entries from {@code model.parameters.tools[]}.
   *
   * @param flagValue the full flag value object
   * @return a map keyed by tool name, or {@code null} when no tools are present
   */
  static Map<String, Tool> resolveTools(LDValue flagValue) {
    LDValue tools = flagValue.get("tools");
    if (tools.getType() == LDValueType.OBJECT) {
      Map<String, Tool> result = new LinkedHashMap<>();
      for (String name : tools.keys()) {
        Tool tool = parseTool(name, tools.get(name));
        if (tool != null) {
          result.put(name, tool);
        }
      }
      return result.isEmpty() ? null : result;
    }

    LDValue rawTools = flagValue.get("model").get("parameters").get("tools");
    if (rawTools.getType() != LDValueType.ARRAY) {
      return null;
    }
    Map<String, Tool> result = new LinkedHashMap<>();
    for (LDValue entry : rawTools.values()) {
      String name = asStringOrNull(entry.get("name"));
      if (name == null) {
        continue;
      }
      Tool tool = parseTool(name, entry);
      if (tool != null) {
        result.put(name, tool);
      }
    }
    return result.isEmpty() ? null : result;
  }

  private static Tool parseTool(String fallbackName, LDValue tool) {
    if (tool == null || tool.getType() != LDValueType.OBJECT) {
      return null;
    }
    String name = asStringOrNull(tool.get("name"));
    if (name == null) {
      name = fallbackName;
    }
    return Tool.builder(name)
        .description(asStringOrNull(tool.get("description")))
        .type(asStringOrNull(tool.get("type")))
        .parameters(LDValueConverter.toMap(tool.get("parameters")))
        .customParameters(LDValueConverter.toMap(tool.get("customParameters")))
        .build();
  }

  /**
   * Parses a {@code judgeConfiguration} object. Judge entries missing a string {@code key} are
   * skipped; a missing or non-numeric {@code samplingRate} defaults to {@code 0.0}.
   *
   * @param judgeConfig the judge configuration value
   * @return a {@link JudgeConfiguration}, or {@code null} if the value is not an object
   */
  static JudgeConfiguration parseJudgeConfiguration(LDValue judgeConfig) {
    if (judgeConfig == null || judgeConfig.getType() != LDValueType.OBJECT) {
      return null;
    }
    LDValue judges = judgeConfig.get("judges");
    List<JudgeConfiguration.Judge> result = new ArrayList<>();
    if (judges.getType() == LDValueType.ARRAY) {
      for (LDValue entry : judges.values()) {
        if (entry == null || entry.getType() != LDValueType.OBJECT) {
          continue;
        }
        String key = asStringOrNull(entry.get("key"));
        if (key == null) {
          continue;
        }
        LDValue rate = entry.get("samplingRate");
        double samplingRate = rate.getType() == LDValueType.NUMBER ? rate.doubleValue() : 0.0;
        result.add(new JudgeConfiguration.Judge(key, samplingRate));
      }
    }
    return new JudgeConfiguration(result);
  }

  /**
   * Resolves the evaluation metric key, preferring the scalar {@code evaluationMetricKey} and
   * falling back to the first non-blank entry of {@code evaluationMetricKeys[]}.
   *
   * @param flagValue the full flag value object
   * @return the resolved metric key, or {@code null} if none is present
   */
  static String resolveEvaluationMetricKey(LDValue flagValue) {
    LDValue single = flagValue.get("evaluationMetricKey");
    if (single.getType() == LDValueType.STRING) {
      String trimmed = trimToNull(single.stringValue());
      if (trimmed != null) {
        return trimmed;
      }
    }
    LDValue many = flagValue.get("evaluationMetricKeys");
    if (many.getType() == LDValueType.ARRAY) {
      for (LDValue entry : many.values()) {
        if (entry.getType() == LDValueType.STRING) {
          String trimmed = trimToNull(entry.stringValue());
          if (trimmed != null) {
            return trimmed;
          }
        }
      }
    }
    return null;
  }

  private static String asStringOrNull(LDValue value) {
    return value != null && value.getType() == LDValueType.STRING ? value.stringValue() : null;
  }

  private static String trimToNull(String s) {
    if (s == null) {
      return null;
    }
    String trimmed = s.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
