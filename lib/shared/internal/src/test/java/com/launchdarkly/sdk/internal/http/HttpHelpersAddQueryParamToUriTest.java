package com.launchdarkly.sdk.internal.http;

import static org.junit.Assert.assertEquals;

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;

import com.launchdarkly.sdk.internal.BaseTest;

@RunWith(Parameterized.class)
public class HttpHelpersAddQueryParamToUriTest extends BaseTest {

    @Parameter(0)
    public URI inputURI;
    @Parameter(1)
    public String inputKey;
    @Parameter(2)
    public String inputValue;
    @Parameter(3)
    public URI expectedURI;

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        // parameters and expected output
        return Arrays.asList(new Object[][] {
                // nice case
                { URI.create("http://1.1.1.1"), "filter", "myFilter", URI.create("http://1.1.1.1/?filter=myFilter") },
                // encoding check
                { URI.create("http://1.1.1.1"), "filter", "encoding necessary +! %& ( )", URI.create("http://1.1.1.1/?filter=encoding%20necessary%20%2B%21%20%25%26%20%28%20%29") },
                // existing param
                { URI.create("http://1.1.1.1/?withReasons=true"), "filter", "myFilter", URI.create("http://1.1.1.1/?withReasons=true&filter=myFilter") },
                // order affects result (just including this for determinism, not a spec point)
                { URI.create("http://1.1.1.1/?filter=myFilter"), "withReasons", "true", URI.create("http://1.1.1.1/?filter=myFilter&withReasons=true") },
                // existing path
                { URI.create("http://1.1.1.1/a/path"), "filter", "myFilter", URI.create("http://1.1.1.1/a/path?filter=myFilter") },

                // below are weird cases that we aren't expecting to encounter, just including for documentation of behavior
                // adding param again
                { URI.create("http://1.1.1.1/?filter=myFilter"), "filter", "anotherFilter", URI.create("http://1.1.1.1/?filter=myFilter&filter=anotherFilter") },
                // adding empty params and values
                { URI.create("http://1.1.1.1/?filter=myFilter"), "", "", URI.create("http://1.1.1.1/?filter=myFilter&=") },
        });
    }

    @Test
    public void TestParametricAddQueryParam() {
        assertEquals(this.expectedURI, HttpHelpers.addQueryParam(this.inputURI, this.inputKey, this.inputValue));
    }

    @Test(expected = IllegalArgumentException.class)
    public void TestImproperURIThrowsException() {
        URI uriUnderTest = URI.create("ImARidiculousURI/?existingparam=existingvalue");
        HttpHelpers.addQueryParam(uriUnderTest, "notImportant", "notImportant");
    }
}
