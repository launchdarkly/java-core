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
     * If the parsing failed, the code will log an error and
     * return a well formed but with nullable value nulled and disabled AIConfig
     * 
     * Doing all the error checks, so if somehow LD backend return incorrect value
     * types, there is logging
     * This also opens up the possibility of allowing customer to build this using a
     * JSON string in the future
     *
     * @param value
     * @param key
     */
    protected AiConfig parseAiConfig(LDValue value, String key) {
        boolean enabled = false;

        // Verify the whole value is a JSON object
        if (!checkValueWithFailureLogging(value, LDValueType.OBJECT, logger,
                "Input to parseAiConfig must be a JSON object")) {
            return new AiConfig(enabled, null, null, null, null);
        }

        // Convert the _meta JSON object into Meta
        LDValue valueMeta = value.get("_ldMeta");
        if (!checkValueWithFailureLogging(valueMeta, LDValueType.OBJECT, logger, "_ldMeta must be a JSON object")) {
            // Q: If we can't read _meta, enabled by spec would be defaulted to false. Does
            // it even matter the rest of the values?
            return new AiConfig(enabled, null, null, null, null);
        }

        // The booleanValue will get false if that value is something that we are not expecting
        enabled = valueMeta.get("enabled").booleanValue();

        Meta meta = null;

        if (checkValueWithFailureLogging(valueMeta.get("variationKey"), LDValueType.STRING, logger,
                "variationKey should be a string")) {
            String variationKey = valueMeta.get("variationKey").stringValue();

            meta = new Meta(
                variationKey,
                Optional.of(valueMeta.get("version").intValue()));
        }

        // Convert the optional model from an JSON object of with parameters and custom
        // into Model
        Model model = null;

        LDValue valueModel = value.get("model");
        if (checkValueWithFailureLogging(valueModel, LDValueType.OBJECT, logger,
                "model if exists must be a JSON object")) {
            if (checkValueWithFailureLogging(valueModel.get("name"), LDValueType.STRING, logger,
                    "model name must be a string and is required")) {
                String modelName = valueModel.get("name").stringValue();

                // Prepare parameters and custom maps for Model
                HashMap<String, LDValue> parameters = null;
                HashMap<String, LDValue> custom = null;

                LDValue valueParameters = valueModel.get("parameters");
                if (checkValueWithFailureLogging(valueParameters, LDValueType.OBJECT, logger,
                        "non-null parameters must be a JSON object")) {
                    parameters = new HashMap<>();
                    for (String k : valueParameters.keys()) {
                        parameters.put(k, valueParameters.get(k));
                    }
                }

                LDValue valueCustom = valueModel.get("custom");
                if (checkValueWithFailureLogging(valueCustom, LDValueType.OBJECT, logger,
                        "non-null custom must be a JSON object")) {

                    custom = new HashMap<>();
                    for (String k : valueCustom.keys()) {
                        custom.put(k, valueCustom.get(k));
                    }
                }

                model = new Model(modelName, parameters, custom);
            }
        }

        // Convert the optional messages from an JSON array of JSON objects into Message
        // Q: Does it even make sense to have 0 messages?
        List<Message> messages = null;

        LDValue valueMessages = value.get("messages");
        if (checkValueWithFailureLogging(valueMessages, LDValueType.ARRAY, logger,
                "messages if exists must be a JSON array")) {
            messages = new ArrayList<Message>();
            valueMessages.valuesAs(new Message.MessageConverter());
            for (Message message : valueMessages.valuesAs(new Message.MessageConverter())) {
                messages.add(message);
            }
        }

        // Convert the optional provider from an JSON object of with name into Provider
        LDValue valueProvider = value.get("provider");
        Provider provider = null;

        if (checkValueWithFailureLogging(valueProvider, LDValueType.OBJECT, logger,
                "non-null provider must be a JSON object")) {
            if (checkValueWithFailureLogging(valueProvider.get("name"), LDValueType.STRING, logger,
                    "provider name must be a String")) {
                String providerName = valueProvider.get("name").stringValue();

                provider = new Provider(providerName);
            }
        }

        return new AiConfig(enabled, meta, model, messages, provider);
    }

    protected boolean checkValueWithFailureLogging(LDValue ldValue, LDValueType expectedType, LDLogger logger,
            String message) {
        if (ldValue.getType() != expectedType) {
            // TODO: In the next PR, make this required with some sort of default logger
            if (logger != null) {
                logger.error(message);
            }
            return false;
        }
        return true;
    }
}
