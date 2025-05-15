package com.launchdarkly.sdk.server.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.LDValueType;
import com.launchdarkly.sdk.server.ai.datamodel.AiConfig;
import com.launchdarkly.sdk.server.ai.datamodel.Message;
import com.launchdarkly.sdk.server.ai.datamodel.Meta;
import com.launchdarkly.sdk.server.ai.datamodel.Model;
import com.launchdarkly.sdk.server.ai.datamodel.Provider;
import com.launchdarkly.sdk.server.ai.datamodel.Role;
import com.launchdarkly.sdk.server.ai.interfaces.LDAiClientInterface;

/**
 * The LaunchDarkly AI client. The client is capable of retrieving AI Configs
 * from LaunchDarkly,
 * and generating events specific to usage of the AI Config when interacting
 * with model providers.
 */
public final class LDAiClient implements LDAiClientInterface {
    private LDClientInterface client;
    private LDLogger logger;

    /**
     * Creates a {@link LDAiClient}
     * 
     * @param client LaunchDarkly Java Server SDK
     */
    public LDAiClient(LDClientInterface client) {
        if (client == null) {
            // Error
        } else {
            this.client = client;
            this.logger = client.getLogger();
        }
    }

    /**
     * Method to convert the JSON variable into the AiConfig object
     *
     * Currently, I am doing this in a mutable way, just to verify the logic convert
     * LDValue into AiConfig.
     * It is possible we need a builder to create immutable version of this for ease
     * of use in a later PR.
     *
     * @param value
     * @param key
     */
    protected AiConfig parseAiConfig(LDValue value, String key) {
        AiConfig result = new AiConfig();

        try {
            // Verify the whole value is a JSON object
            if (value == null || value.getType() != LDValueType.OBJECT) {
                throw new AiConfigParseException("Input to parseAiConfig must be a JSON object");
            }

            // Convert the _meta JSON object into Meta
            LDValue valueMeta = value.get("_ldMeta");
            if (valueMeta == LDValue.ofNull() || valueMeta.getType() != LDValueType.OBJECT) {
                throw new AiConfigParseException("_ldMeta must be a JSON object");
            }

            Meta meta = new Meta();
            // TODO: Do we expect customer calling this to build default value?
            // If we do, then some of the values would be null
            meta.setEnabled(ldValueNullCheck(valueMeta.get("enabled")).booleanValue());
            meta.setVariationKey(ldValueNullCheck(valueMeta.get("variationKey")).stringValue());
            Optional<Integer> version = Optional.of(valueMeta.get("version").intValue());
            meta.setVersion(version);
            result.setMeta(meta);

            // Convert the optional messages from an JSON array of JSON objects into Message
            // Q: Does it even make sense to have 0 messages?
            LDValue valueMessages = value.get("messages");
            if (valueMeta == LDValue.ofNull() || valueMessages.getType() != LDValueType.ARRAY) {
                throw new AiConfigParseException("messages must be a JSON array");
            }

            List<Message> messages = new ArrayList<Message>();
            valueMessages.valuesAs(new Message.MessageConverter());
            for (Message message : valueMessages.valuesAs(new Message.MessageConverter())) {
                messages.add(message);
            }
            result.setMessages(messages);

            // Convert the optional model from an JSON object of with parameters and custom
            // into Model
            LDValue valueModel = value.get("model");
            if (valueModel == LDValue.ofNull() || valueModel.getType() != LDValueType.OBJECT) {
                throw new AiConfigParseException("model must be a JSON object");
            }

            Model model = new Model();
            model.setName(ldValueNullCheck(valueModel.get("name")).stringValue());

            LDValue valueParameters = valueModel.get("parameters");
            if (valueParameters.getType() != LDValueType.NULL) {
                if (valueParameters.getType() != LDValueType.OBJECT) {
                    throw new AiConfigParseException("non-null parameters must be a JSON object");
                }

                HashMap<String, LDValue> parameters = new HashMap<>();
                for (String k : valueParameters.keys()) {
                    parameters.put(k, valueParameters.get(k));
                }
                model.setParameters(parameters);
            } else {
                // Parameters is optional - so we can just set null and proceed

                // TODO: Mustash validation somewhere
                model.setParameters(null);
            }

            LDValue valueCustom = valueModel.get("custom");
            if (valueCustom.getType() != LDValueType.NULL) {
                if (valueCustom.getType() != LDValueType.OBJECT) {
                    throw new AiConfigParseException("non-null custom must be a JSON object");
                }

                HashMap<String, LDValue> custom = new HashMap<>();
                for (String k : valueCustom.keys()) {
                    custom.put(k, valueCustom.get(k));
                }
                model.setCustom(custom);
            } else {
                // Custom is optional - we can just set null and proceed
                model.setCustom(null);
            }
            result.setModel(model);

            // Convert the optional provider from an JSON object of with name into Provider
            LDValue valueProvider = value.get("provider");
            if (valueProvider.getType() != LDValueType.NULL) {
                if (valueProvider.getType() != LDValueType.OBJECT) {
                    throw new AiConfigParseException("non-null provider must be a JSON object");
                }

                Provider provider = new Provider(ldValueNullCheck(valueProvider.get("name")).stringValue());
                result.setProvider(provider);
            } else {
                // Provider is optional - we can just set null and proceed
                result.setProvider(null);
            }
        } catch (AiConfigParseException e) {
            // logger.error(e.getMessage());
            return null;
        }

        return result;
    }

    protected <T> T ldValueNullCheck(T ldValue) throws AiConfigParseException {
        if (ldValue == LDValue.ofNull()) {
            throw new AiConfigParseException("Unexpected Null value for non-optional field");
        }
        return ldValue;
    }

    class AiConfigParseException extends Exception {
        AiConfigParseException(String exceptionMessage) {
            super(exceptionMessage);
        }
    }
}
