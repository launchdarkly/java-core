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
public class HttpHelpersConcatUriPathTest extends BaseTest {

    @Parameter(0)
    public URI inputURI;
    @Parameter(1)
    public String inputPath;
    @Parameter(2)
    public URI expectedURI;

    @Parameterized.Parameters
    public static Collection<Object[]> data() {

        // parameters and expected output
        return Arrays.asList(new Object[][] {
                { URI.create("http://1.1.1.1"), "/status", URI.create("http://1.1.1.1/status") },
                { URI.create("http://1.1.1.1/"), "/status", URI.create("http://1.1.1.1/status") },
                { URI.create("http://1.1.1.1/"), "//status", URI.create("http://1.1.1.1/status") },
                { URI.create("http://google.com"), "/status", URI.create("http://google.com/status") },
                { URI.create("http://google.com"), "//status", URI.create("http://google.com/status") },
                { URI.create("http://google.com"), "///status", URI.create("http://google.com/status") },
                { URI.create("http://google.com/"), "/status", URI.create("http://google.com/status") },
                { URI.create("http://google.com/"), "//status", URI.create("http://google.com/status") },
                { URI.create("http://google.com/"), "///status", URI.create("http://google.com/status") },
                { URI.create("http://google.com//"), "/status", URI.create("http://google.com/status") },
                { URI.create("http://google.com//"), "//status", URI.create("http://google.com/status") },
                { URI.create("http://google.com//"), "///status", URI.create("http://google.com/status") },
                { URI.create("http://google.com///"), "/status", URI.create("http://google.com/status") },
                { URI.create("http://google.com///"), "//status", URI.create("http://google.com/status") },
                { URI.create("http://google.com///"), "///status", URI.create("http://google.com/status") },
                { URI.create("https://google.com"), "/status", URI.create("https://google.com/status") },
                { URI.create("https://google.com"), "//status", URI.create("https://google.com/status") },
                { URI.create("https://google.com"), "///status", URI.create("https://google.com/status") },
                { URI.create("https://google.com/"), "/status", URI.create("https://google.com/status") },
                { URI.create("https://google.com/"), "//status", URI.create("https://google.com/status") },
                { URI.create("https://google.com/"), "///status", URI.create("https://google.com/status") },
                { URI.create("https://google.com//"), "/status", URI.create("https://google.com/status") },
                { URI.create("https://google.com//"), "//status", URI.create("https://google.com/status") },
                { URI.create("https://google.com//"), "///status", URI.create("https://google.com/status") },
                { URI.create("https://google.com///"), "/status", URI.create("https://google.com/status") },
                { URI.create("https://google.com///"), "//status", URI.create("https://google.com/status") },
                { URI.create("https://google.com///"), "///status", URI.create("https://google.com/status") },
                { URI.create("https://google.com:1234"), "/status", URI.create("https://google.com:1234/status") },
                { URI.create("https://google.com:1234"), "//status", URI.create("https://google.com:1234/status") },
                { URI.create("https://google.com:1234"), "///status", URI.create("https://google.com:1234/status") },
                { URI.create("https://google.com:1234/"), "/status", URI.create("https://google.com:1234/status") },
                { URI.create("https://google.com:1234/"), "//status", URI.create("https://google.com:1234/status") },
                { URI.create("https://google.com:1234/"), "///status", URI.create("https://google.com:1234/status") },
                { URI.create("https://google.com:1234//"), "/status", URI.create("https://google.com:1234/status") },
                { URI.create("https://google.com:1234//"), "//status", URI.create("https://google.com:1234/status") },
                { URI.create("https://google.com:1234//"), "///status", URI.create("https://google.com:1234/status") },
                { URI.create("https://google.com:1234///"), "/status", URI.create("https://google.com:1234/status") },
                { URI.create("https://google.com:1234///"), "//status", URI.create("https://google.com:1234/status") },
                { URI.create("https://google.com:1234///"), "///status", URI.create("https://google.com:1234/status") },

                // test to make sure query params don't get removed by append
                { URI.create("https://google.com:1234/some/root/path/?filter=myFilter"), "/toAppend", URI.create("https://google.com:1234/some/root/path/toAppend?filter=myFilter") },
        });
    }

    @Test
    public void TestConcatenateUriPath() {
        assertEquals(this.expectedURI, HttpHelpers.concatenateUriPath(this.inputURI, this.inputPath));
    }
}
