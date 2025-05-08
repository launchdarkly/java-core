package com.launchdarkly.sdk.server.ai;

import org.junit.Test;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.ai.datamodel.AiConfig;
import com.launchdarkly.sdk.server.ai.datamodel.Role;

import static org.junit.Assert.assertEquals;

public class LDAiClientTest {
    @Test 
    public void testParseAiConfig() {
        String rawJson = "{\n" + //
                        "  \"_ldMeta\": {\n" + //
                        "    \"variationKey\" : 1234,\n" + //
                        "    \"enabled\": true,\n" + //
                        "    \"version\": 1\n" + //
                        "  },\n" + //
                        "  \"messages\": [\n" + //
                        "    {\n" + //
                        "      \"content\": \"This is an {{ adjective }} message.\",\n" + //
                        "      \"role\": \"user\"\n" + //
                        "    },\n" + //
                        "    {\n" + //
                        "      \"content\": \"{{ greeting}}, this is another message!\",\n" + //
                        "      \"role\": \"system\"\n" + //
                        "    },\n" + //
                        "    {\n" + //
                        "      \"content\": \"This is the final {{ noun }}.\",\n" + //
                        "      \"role\": \"assistant\"\n" + //
                        "    }\n" + //
                        "  ],\n" + //
                        "  \"model\": {\n" + //
                        "    \"name\": \"my-cool-custom-model\",\n" + //
                        "    \"parameters\": {\n" + //
                        "      \"foo\" : \"bar\",\n" + //
                        "      \"baz\" : 23,\n" + //
                        "      \"qux\" : true,\n" + //
                        "      \"whatever\" : [],\n" + //
                        "      \"another\" : {}\n" + //
                        "    }\n" + //
                        "  },\n" + //
                        "  \"provider\": {\n" + //
                        "    \"name\" : \"provider-name\"\n" + //
                        "  }\n" + //
                        "}";

        LDValue input = LDValue.parse(rawJson);
            
        LDAiClient client = new LDAiClient(null);

        AiConfig result = client.parseAiConfig(input, "Whatever");

        assertEquals(Role.USER, result.getMessages().get(0).getRole());
        assertEquals(Integer.valueOf(1), result.getMeta().getVersion().orElse(0));
        assertEquals(LDValue.of(true), result.getModel().getParameters().get("qux"));
        assertEquals("provider-name", result.getProvider().getName());
    };
}
