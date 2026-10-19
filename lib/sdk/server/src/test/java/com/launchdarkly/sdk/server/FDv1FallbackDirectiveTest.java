package com.launchdarkly.sdk.server;

import com.google.gson.JsonParser;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.server.datasources.FDv1FallbackDirective;

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
    private static void assertJitteredDefault(FDv1FallbackDirective directive) {
        assertThat(directive.getTtl(), allOf(
            greaterThanOrEqualTo(Duration.ofMinutes(30)),
            lessThanOrEqualTo(Duration.ofHours(1))));
    }

    private static FDv2Event goodbye(String json) {
        return new FDv2Event("goodbye", JsonParser.parseString(json));
    }

    @Test
    public void validServiceTtlIsUsedAsIs() {
        assertEquals(Duration.ofSeconds(1), FDv1FallbackDirective.fromServiceTtl(Duration.ofSeconds(1)).getTtl());
        assertEquals(Duration.ofSeconds(90), FDv1FallbackDirective.fromServiceTtl(Duration.ofSeconds(90)).getTtl());
        assertEquals(Duration.ofHours(1), FDv1FallbackDirective.fromServiceTtl(Duration.ofHours(1)).getTtl());
        assertEquals(Duration.ofSeconds(90), FDv1FallbackDirective.fromServiceTtl("90").getTtl());
    }

    @Test
    public void invalidServiceTtlUsesJitteredDefault() {
        assertJitteredDefault(FDv1FallbackDirective.fromServiceTtl((Duration) null));
        assertJitteredDefault(FDv1FallbackDirective.fromServiceTtl(Duration.ZERO));
        assertJitteredDefault(FDv1FallbackDirective.fromServiceTtl(Duration.ofSeconds(-1)));
        assertJitteredDefault(FDv1FallbackDirective.fromServiceTtl(Duration.ofSeconds(3601)));
        assertJitteredDefault(FDv1FallbackDirective.fromServiceTtl((String) null));
        assertJitteredDefault(FDv1FallbackDirective.fromServiceTtl(""));
        assertJitteredDefault(FDv1FallbackDirective.fromServiceTtl("0"));
        assertJitteredDefault(FDv1FallbackDirective.fromServiceTtl("-5"));
        assertJitteredDefault(FDv1FallbackDirective.fromServiceTtl("3601"));
        assertJitteredDefault(FDv1FallbackDirective.fromServiceTtl("1.5"));
        assertJitteredDefault(FDv1FallbackDirective.fromServiceTtl("soon"));
        assertJitteredDefault(FDv1FallbackDirective.fromServiceTtl("99999999999999999999"));
    }

    @Test
    public void defaultTtlIsJittered() {
        for (int i = 0; i < 100; i++) {
            assertJitteredDefault(FDv1FallbackDirective.defaultTtl());
        }
    }

    @Test
    public void headersWithoutFallbackHaveNoDirective() {
        assertNull(FDv1FallbackDirectives.fromHeaders(null, "60"));
        assertNull(FDv1FallbackDirectives.fromHeaders("false", "60"));
    }

    @Test
    public void headersWithFallbackUseTtlHeader() {
        assertEquals(Duration.ofSeconds(60), FDv1FallbackDirectives.fromHeaders("true", "60").getTtl());
        assertEquals(Duration.ofSeconds(60), FDv1FallbackDirectives.fromHeaders("TRUE", "60").getTtl());
        assertJitteredDefault(FDv1FallbackDirectives.fromHeaders("true", null));
        assertJitteredDefault(FDv1FallbackDirectives.fromHeaders("true", "0"));
    }

    @Test
    public void goodbyeWithoutProtocolFallbackTtlHasNoDirective() {
        assertNull(FDv1FallbackDirectives.fromGoodbye(goodbye("{\"reason\":\"bye\"}")));
        assertNull(FDv1FallbackDirectives.fromGoodbye(goodbye("{\"reason\":\"bye\",\"protocolFallbackTTL\":null}")));
    }

    @Test
    public void goodbyeWithProtocolFallbackTtlIsDirective() {
        assertEquals(Duration.ofSeconds(120),
            FDv1FallbackDirectives.fromGoodbye(goodbye("{\"reason\":\"bye\",\"protocolFallbackTTL\":120}")).getTtl());
        assertJitteredDefault(FDv1FallbackDirectives.fromGoodbye(goodbye("{\"protocolFallbackTTL\":0}")));
        assertJitteredDefault(FDv1FallbackDirectives.fromGoodbye(goodbye("{\"protocolFallbackTTL\":-1}")));
        assertJitteredDefault(FDv1FallbackDirectives.fromGoodbye(goodbye("{\"protocolFallbackTTL\":7200}")));
        assertJitteredDefault(FDv1FallbackDirectives.fromGoodbye(goodbye("{\"protocolFallbackTTL\":\"soon\"}")));
    }
}
