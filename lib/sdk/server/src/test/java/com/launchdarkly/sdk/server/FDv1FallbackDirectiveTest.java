package com.launchdarkly.sdk.server;

import com.google.gson.JsonParser;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;

import org.junit.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@SuppressWarnings("javadoc")
public class FDv1FallbackDirectiveTest {
    private static void assertJitteredDefault(Duration ttl) {
        assertThat(ttl, allOf(
            greaterThanOrEqualTo(Duration.ofMinutes(30)),
            lessThanOrEqualTo(Duration.ofHours(1))));
    }

    private static FDv2Event goodbye(String json) {
        return new FDv2Event("goodbye", JsonParser.parseString(json));
    }

    @Test
    public void validServiceTtlIsUsedAsIs() {
        assertEquals(Duration.ofMillis(1), FDv1FallbackDirective.ttlOrDefault(Duration.ofMillis(1)));
        assertEquals(Duration.ofSeconds(90), FDv1FallbackDirective.ttlOrDefault(Duration.ofSeconds(90)));
        assertEquals(Duration.ofHours(1), FDv1FallbackDirective.ttlOrDefault(Duration.ofHours(1)));
    }

    @Test
    public void invalidServiceTtlUsesJitteredDefault() {
        assertJitteredDefault(FDv1FallbackDirective.ttlOrDefault(null));
        assertJitteredDefault(FDv1FallbackDirective.ttlOrDefault(Duration.ZERO));
        assertJitteredDefault(FDv1FallbackDirective.ttlOrDefault(Duration.ofSeconds(-1)));
        assertJitteredDefault(FDv1FallbackDirective.ttlOrDefault(Duration.ofSeconds(3601)));
    }

    @Test
    public void defaultTtlIsJittered() {
        for (int i = 0; i < 100; i++) {
            assertJitteredDefault(FDv1FallbackDirective.ttlOrDefault(null));
        }
    }

    @Test
    public void headersWithoutFallbackHaveNoDirective() {
        assertNull(FDv1FallbackDirective.fromHeaders(null, "60"));
        assertNull(FDv1FallbackDirective.fromHeaders("false", "60"));
    }

    @Test
    public void headersWithFallbackUseValidTtlHeaderAsIs() {
        assertEquals(Duration.ofSeconds(60), FDv1FallbackDirective.fromHeaders("true", "60"));
        assertEquals(Duration.ofSeconds(60), FDv1FallbackDirective.fromHeaders("TRUE", "60"));
        assertEquals(Duration.ofHours(1), FDv1FallbackDirective.fromHeaders("true", "3600"));
    }

    @Test
    public void headersWithFallbackAndInvalidTtlHeaderUseJitteredDefault() {
        assertJitteredDefault(FDv1FallbackDirective.fromHeaders("true", null));
        assertJitteredDefault(FDv1FallbackDirective.fromHeaders("true", ""));
        assertJitteredDefault(FDv1FallbackDirective.fromHeaders("true", "0"));
        assertJitteredDefault(FDv1FallbackDirective.fromHeaders("true", "-5"));
        assertJitteredDefault(FDv1FallbackDirective.fromHeaders("true", "3601"));
        assertJitteredDefault(FDv1FallbackDirective.fromHeaders("true", "1.5"));
        assertJitteredDefault(FDv1FallbackDirective.fromHeaders("true", "soon"));
        assertJitteredDefault(FDv1FallbackDirective.fromHeaders("true", "99999999999999999999"));
    }

    @Test
    public void goodbyeWithoutProtocolFallbackTtlHasNoDirective() {
        assertNull(FDv1FallbackDirective.fromGoodbye(goodbye("{\"reason\":\"bye\"}")));
        assertNull(FDv1FallbackDirective.fromGoodbye(goodbye("{\"reason\":\"bye\",\"protocolFallbackTTL\":null}")));
    }

    @Test
    public void goodbyeWithValidProtocolFallbackTtlIsUsedAsIs() {
        assertEquals(Duration.ofSeconds(120),
            FDv1FallbackDirective.fromGoodbye(goodbye("{\"reason\":\"bye\",\"protocolFallbackTTL\":120}")));
    }

    @Test
    public void goodbyeWithInvalidProtocolFallbackTtlUsesJitteredDefault() {
        assertJitteredDefault(FDv1FallbackDirective.fromGoodbye(goodbye("{\"protocolFallbackTTL\":0}")));
        assertJitteredDefault(FDv1FallbackDirective.fromGoodbye(goodbye("{\"protocolFallbackTTL\":-1}")));
        assertJitteredDefault(FDv1FallbackDirective.fromGoodbye(goodbye("{\"protocolFallbackTTL\":7200}")));
        assertJitteredDefault(FDv1FallbackDirective.fromGoodbye(goodbye("{\"protocolFallbackTTL\":\"soon\"}")));
    }
}
