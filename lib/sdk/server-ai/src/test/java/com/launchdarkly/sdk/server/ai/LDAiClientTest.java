package com.launchdarkly.sdk.server.ai;

import org.junit.Test;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.ai.config.LDAiConfig;
import com.launchdarkly.sdk.server.ai.datamodel.Role;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class LDAiClientTest {
    /**
     * Tests that a complete valid JSON is properly converted to an AiConfig object
     */
    @Test
    public void testCompleteAIConfig() {
        String rawJson = "{\n" + //
                "  \"_ldMeta\": {\n" + //
                "    \"variationKey\" : \"1234\",\n" + //
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
        LDAiConfig result = client.parseAiConfig(input, "Whatever");

        assertEquals(Role.USER, result.getMessages().get(0).getRole());
        assertEquals("This is the final {{ noun }}.", result.getMessages().get(2).getContent());
        assertEquals(Integer.valueOf(1), result.getMeta().getVersion().orElse(0));
        assertEquals(LDValue.of(true), result.getModel().getParameters().get("qux"));
        assertEquals("provider-name", result.getProvider().getName());
        assertTrue(result.isEnabled());
    }

    /**
     * Tests that a valid Meta object is correctly parsed
     */
    @Test
    public void testValidMeta() {
        String json = "{\n" + //
                "  \"_ldMeta\": {\n" + //
                "    \"variationKey\" : \"key-123\",\n" + //
                "    \"enabled\": true,\n" + //
                "    \"version\": 42\n" + //
                "  },\n" + //
                "  \"messages\": [{\n" + //
                "    \"content\": \"content\",\n" + //
                "    \"role\": \"user\"\n" + //
                "  }],\n" + //
                "  \"model\": {\n" + //
                "    \"name\": \"model-name\"\n" + //
                "  },\n" + //
                "  \"provider\": {\n" + //
                "    \"name\" : \"provider-name\"\n" + //
                "  }\n" + //
                "}";

        LDValue input = LDValue.parse(json);
        LDAiClient client = new LDAiClient(null);
        LDAiConfig result = client.parseAiConfig(input, "Whatever");

        assertNotNull(result.getMeta());
        assertEquals("key-123", result.getMeta().getVariationKey());
        assertEquals(Integer.valueOf(42), result.getMeta().getVersion().orElse(0));
        assertTrue(result.isEnabled());
    }

    /**
     * Tests that invalid Meta with number as variationKey is handled properly
     */
    @Test
    public void testInvalidMetaNumberAsVariationKey() {
        String json = "{\n" + //
                "  \"_ldMeta\": {\n" + //
                "    \"variationKey\" : 123,\n" + //
                "    \"enabled\": true,\n" + //
                "    \"version\": 1\n" + //
                "  },\n" + //
                "  \"messages\": [{\n" + //
                "    \"content\": \"content\",\n" + //
                "    \"role\": \"user\"\n" + //
                "  }],\n" + //
                "  \"model\": {\n" + //
                "    \"name\": \"model-name\"\n" + //
                "  },\n" + //
                "  \"provider\": {\n" + //
                "    \"name\" : \"provider-name\"\n" + //
                "  }\n" + //
                "}";

        LDValue input = LDValue.parse(json);
        LDAiClient client = new LDAiClient(null);
        LDAiConfig result = client.parseAiConfig(input, "Whatever");

        assertNull(result.getMeta());
    }

    /**
     * Tests that valid Messages are correctly parsed
     */
    @Test
    public void testValidMessages() {
        String json = "{\n" + //
                "  \"_ldMeta\": {\n" + //
                "    \"variationKey\" : \"key-123\",\n" + //
                "    \"enabled\": true,\n" + //
                "    \"version\": 1\n" + //
                "  },\n" + //
                "  \"messages\": [\n" + //
                "    {\n" + //
                "      \"content\": \"User message\",\n" + //
                "      \"role\": \"user\"\n" + //
                "    },\n" + //
                "    {\n" + //
                "      \"content\": \"System message\",\n" + //
                "      \"role\": \"system\"\n" + //
                "    },\n" + //
                "    {\n" + //
                "      \"content\": \"Assistant message\",\n" + //
                "      \"role\": \"assistant\"\n" + //
                "    }\n" + //
                "  ],\n" + //
                "  \"model\": {\n" + //
                "    \"name\": \"model-name\"\n" + //
                "  },\n" + //
                "  \"provider\": {\n" + //
                "    \"name\" : \"provider-name\"\n" + //
                "  }\n" + //
                "}";

        LDValue input = LDValue.parse(json);
        LDAiClient client = new LDAiClient(null);
        LDAiConfig result = client.parseAiConfig(input, "Whatever");

        assertNotNull(result.getMessages());
        assertEquals(3, result.getMessages().size());

        assertEquals(Role.USER, result.getMessages().get(0).getRole());
        assertEquals("User message", result.getMessages().get(0).getContent());

        assertEquals(Role.SYSTEM, result.getMessages().get(1).getRole());
        assertEquals("System message", result.getMessages().get(1).getContent());

        assertEquals(Role.ASSISTANT, result.getMessages().get(2).getRole());
        assertEquals("Assistant message", result.getMessages().get(2).getContent());
    }

    /**
     * Tests that invalid Messages with wrong role type are handled properly
     */
    @Test
    public void testInvalidMessagesInvalidRole() {
        String json = "{\n" + //
                "  \"_ldMeta\": {\n" + //
                "    \"variationKey\" : \"key\",\n" + //
                "    \"enabled\": true,\n" + //
                "    \"version\": 1\n" + //
                "  },\n" + //
                "  \"messages\": [\n" + //
                "    {\n" + //
                "      \"content\": \"Invalid role\",\n" + //
                "      \"role\": \"invalid_role_value\"\n" + //
                "    }\n" + //
                "  ],\n" + //
                "  \"model\": {\n" + //
                "    \"name\": \"model-name\"\n" + //
                "  },\n" + //
                "  \"provider\": {\n" + //
                "    \"name\" : \"provider-name\"\n" + //
                "  }\n" + //
                "}";

        LDValue input = LDValue.parse(json);
        LDAiClient client = new LDAiClient(null);
        LDAiConfig result = client.parseAiConfig(input, "Whatever");

        assertNotNull(result.getMessages());
        assertEquals(1, result.getMessages().size());
        assertNull(result.getMessages().get(0).getRole());
    }

    /**
     * Tests that a valid Model object is correctly parsed
     */
    @Test
    public void testValidModel() {
        String json = "{\n" + //
                "  \"_ldMeta\": {\n" + //
                "    \"variationKey\" : \"key\",\n" + //
                "    \"enabled\": true,\n" + //
                "    \"version\": 1\n" + //
                "  },\n" + //
                "  \"messages\": [{\n" + //
                "    \"content\": \"content\",\n" + //
                "    \"role\": \"user\"\n" + //
                "  }],\n" + //
                "  \"model\": {\n" + //
                "    \"name\": \"test-model\",\n" + //
                "    \"parameters\": {\n" + //
                "      \"string_param\" : \"value\",\n" + //
                "      \"number_param\" : 42,\n" + //
                "      \"bool_param\" : true,\n" + //
                "      \"array_param\" : [1, 2, 3],\n" + //
                "      \"object_param\" : {\"key\": \"value\"}\n" + //
                "    },\n" + //
                "    \"custom\": {\n" + //
                "      \"custom_key\": \"custom_value\"\n" + //
                "    }\n" + //
                "  },\n" + //
                "  \"provider\": {\n" + //
                "    \"name\" : \"provider-name\"\n" + //
                "  }\n" + //
                "}";

        LDValue input = LDValue.parse(json);
        LDAiClient client = new LDAiClient(null);
        LDAiConfig result = client.parseAiConfig(input, "Whatever");

        assertNotNull(result.getModel());
        assertEquals("test-model", result.getModel().getName());

        assertNotNull(result.getModel().getParameters());
        assertEquals(5, result.getModel().getParameters().size());
        assertEquals(LDValue.of("value"), result.getModel().getParameters().get("string_param"));
        assertEquals(LDValue.of(42), result.getModel().getParameters().get("number_param"));
        assertEquals(LDValue.of(true), result.getModel().getParameters().get("bool_param"));

        assertNotNull(result.getModel().getCustom());
        assertEquals(1, result.getModel().getCustom().size());
        assertEquals(LDValue.of("custom_value"), result.getModel().getCustom().get("custom_key"));
    }

    /**
     * Tests that invalid Model with name as non-string is handled properly
     */
    @Test
    public void testInvalidModelNameType() {
        String json = "{\n" + //
                "  \"_ldMeta\": {\n" + //
                "    \"variationKey\" : \"key\",\n" + //
                "    \"enabled\": true,\n" + //
                "    \"version\": 1\n" + //
                "  },\n" + //
                "  \"messages\": [{\n" + //
                "    \"content\": \"content\",\n" + //
                "    \"role\": \"user\"\n" + //
                "  }],\n" + //
                "  \"model\": {\n" + //
                "    \"name\": 123,\n" + //
                "    \"parameters\": {}\n" + //
                "  },\n" + //
                "  \"provider\": {\n" + //
                "    \"name\" : \"provider-name\"\n" + //
                "  }\n" + //
                "}";

        LDValue input = LDValue.parse(json);
        LDAiClient client = new LDAiClient(null);
        LDAiConfig result = client.parseAiConfig(input, "Whatever");

        assertNull(result.getModel());
    }

    /**
     * Tests that a valid Provider object is correctly parsed
     */
    @Test
    public void testValidProvider() {
        String json = "{\n" + //
                "  \"_ldMeta\": {\n" + //
                "    \"variationKey\" : \"key\",\n" + //
                "    \"enabled\": true,\n" + //
                "    \"version\": 1\n" + //
                "  },\n" + //
                "  \"messages\": [{\n" + //
                "    \"content\": \"content\",\n" + //
                "    \"role\": \"user\"\n" + //
                "  }],\n" + //
                "  \"model\": {\n" + //
                "    \"name\": \"model-name\"\n" + //
                "  },\n" + //
                "  \"provider\": {\n" + //
                "    \"name\" : \"test-provider\"\n" + //
                "  }\n" + //
                "}";

        LDValue input = LDValue.parse(json);
        LDAiClient client = new LDAiClient(null);
        LDAiConfig result = client.parseAiConfig(input, "Whatever");

        assertNotNull(result.getProvider());
        assertEquals("test-provider", result.getProvider().getName());
    }

    /**
     * Tests that invalid Provider with name as non-string is handled properly
     */
    @Test
    public void testInvalidProviderNameType() {
        String json = "{\n" + //
                "  \"_ldMeta\": {\n" + //
                "    \"variationKey\" : \"key\",\n" + //
                "    \"enabled\": true,\n" + //
                "    \"version\": 1\n" + //
                "  },\n" + //
                "  \"messages\": [{\n" + //
                "    \"content\": \"content\",\n" + //
                "    \"role\": \"user\"\n" + //
                "  }],\n" + //
                "  \"model\": {\n" + //
                "    \"name\": \"model-name\"\n" + //
                "  },\n" + //
                "  \"provider\": {\n" + //
                "    \"name\" : 123\n" + //
                "  }\n" + //
                "}";

        LDValue input = LDValue.parse(json);
        LDAiClient client = new LDAiClient(null);
        LDAiConfig result = client.parseAiConfig(input, "Whatever");

        assertNull(result.getProvider());
    }

    /**
     * Tests that a completely invalid JSON input (not an object) is handled
     * properly
     */
    @Test
    public void testInvalidJsonNotObject() {
        String json = "[]"; // Array instead of object

        LDValue input = LDValue.parse(json);
        LDAiClient client = new LDAiClient(null);
        LDAiConfig result = client.parseAiConfig(input, "Whatever");

        assertFalse(result.isEnabled());
        assertNull(result.getMeta());
        assertNull(result.getModel());
        assertNull(result.getMessages());
        assertNull(result.getProvider());
    }

    /**
     * Tests that a JSON with missing required properties is handled properly
     */
    @Test
    public void testMissingRequiredProperties() {
        String json = "{\n" + //
                "  \"_ldMeta\": {\n" + //
                "    \"enabled\": true\n" + //
                "  }\n" + //
                "}";

        LDValue input = LDValue.parse(json);
        LDAiClient client = new LDAiClient(null);
        LDAiConfig result = client.parseAiConfig(input, "Whatever");

        assertTrue(result.isEnabled()); // enabled is present and true
        assertNull(result.getMeta()); // Meta should be null due to missing variationKey
        assertNull(result.getModel());
        assertNull(result.getMessages());
        assertNull(result.getProvider());
    }
}
