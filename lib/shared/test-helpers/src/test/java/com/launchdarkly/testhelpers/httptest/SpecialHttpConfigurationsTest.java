package com.launchdarkly.testhelpers.httptest;

import com.launchdarkly.testhelpers.httptest.SpecialHttpConfigurations.Params;
import com.launchdarkly.testhelpers.httptest.SpecialHttpConfigurations.UnexpectedResponseException;

import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;

import static org.junit.Assert.assertFalse;

import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

@SuppressWarnings("javadoc")
public class SpecialHttpConfigurationsTest {
  private static final int EXPECTED_STATUS = 418;
  
  // An implementation of TestAction that just converts the specified parameters to OkHttp settings
  // and makes a request with the OkHttp client.
  public static class MyTestClientAction implements SpecialHttpConfigurations.TestAction {
    @Override
    public boolean doTest(URI targetUri, Params params) throws IOException, SpecialHttpConfigurations.UnexpectedResponseException {
      OkHttpClient.Builder cb = new OkHttpClient.Builder();
      if (params.getTlsConfig() != null && params.getTlsConfig().getSocketFactory() != null) {
        cb.sslSocketFactory(params.getTlsConfig().getSocketFactory(), params.getTlsConfig().getTrustManager());
      }
      if (params.getSocketFactory() != null) {
        cb.socketFactory(params.getSocketFactory());
      }
      if (params.getProxyHost() != null) {
        cb.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(params.getProxyHost(), params.getProxyPort())));
        if (params.getProxyBasicAuthUser() != null) {
          cb.proxyAuthenticator(new Authenticator() {
            public Request authenticate(Route route, Response response) throws IOException {
              return response.request().newBuilder()
                  .header("Proxy-Authorization",
                      Credentials.basic(params.getProxyBasicAuthUser(), params.getProxyBasicAuthPassword()))
                  .build();
            }
          });
        }
      }

      OkHttpClient client = cb.build();
      
      Request req = new Request.Builder().url(targetUri.toString()).build();
      
      Response resp = client.newCall(req).execute();
      if (resp.code() != EXPECTED_STATUS) { // we know we will be configuring the server with testActionHandler()
        throw new SpecialHttpConfigurations.UnexpectedResponseException("got unexpected response status " + resp.code());
      }

      return true;
    }
  }
  
  private static Handler testActionHandler() {
    return Handlers.status(EXPECTED_STATUS);
  }
  
  @Test
  public void testAllCorrect() {
    SpecialHttpConfigurations.testAll(testActionHandler(), new MyTestClientAction());
  }

  @Test
  public void testSelfSignedCertFails() {
    SpecialHttpConfigurations.TestAction testActionThatIgnoresTlsConfigParam = new SpecialHttpConfigurations.TestAction() {
      @Override
      public boolean doTest(URI targetUri, Params params) throws IOException, UnexpectedResponseException {
        params = new Params(null,
           params.getSocketFactory(),  params.getProxyHost(), params.getProxyPort(),
           params.getProxyBasicAuthUser(), params.getProxyBasicAuthPassword());
        return new MyTestClientAction().doTest(targetUri, params);
      }
    };
    boolean passed = false;
    try {
      SpecialHttpConfigurations.testHttpClientCanBeConfiguredToAllowSelfSignedCert(testActionHandler(),
          testActionThatIgnoresTlsConfigParam);
      passed = true;
    } catch (AssertionError e) {}
    assertFalse("expected test to fail", passed);
  }
  
  @Test
  public void testSocketFactoryFails() {
    SpecialHttpConfigurations.TestAction testActionThatIgnoresSocketFactoryParam = new SpecialHttpConfigurations.TestAction() {
      @Override
      public boolean doTest(URI targetUri, Params params) throws IOException, UnexpectedResponseException {
        params = new Params(params.getTlsConfig(),
            null,
            params.getProxyHost(), params.getProxyPort(), params.getProxyBasicAuthUser(), params.getProxyBasicAuthPassword());
        return new MyTestClientAction().doTest(targetUri, params);
      }
    };
    boolean passed = false;
    try {
      SpecialHttpConfigurations.testHttpClientCanUseCustomSocketFactory(testActionHandler(),
          testActionThatIgnoresSocketFactoryParam);
      passed = true;
    } catch (AssertionError e) {}
    assertFalse("expected test to fail", passed);
  }
  
  @Test
  public void testProxyFails() {
    SpecialHttpConfigurations.TestAction testActionThatIgnoresProxyParams = new SpecialHttpConfigurations.TestAction() {
      @Override
      public boolean doTest(URI targetUri, Params params) throws IOException, UnexpectedResponseException {
        params = new Params(params.getTlsConfig(), params.getSocketFactory(),
            null, 0,
            params.getProxyBasicAuthUser(), params.getProxyBasicAuthPassword());
        return new MyTestClientAction().doTest(targetUri, params);
      }
    };
    boolean passed = false;
    try {
      SpecialHttpConfigurations.testHttpClientCanUseProxy(testActionHandler(),
          testActionThatIgnoresProxyParams);
      passed = true;
    } catch (AssertionError e) {}
    assertFalse("expected test to fail", passed);
  }
  
  @Test
  public void testProxyAuthFailsWithNoAuthProvided() {
    SpecialHttpConfigurations.TestAction testActionThatIgnoresProxyAuthParams = new SpecialHttpConfigurations.TestAction() {
      @Override
      public boolean doTest(URI targetUri, Params params) throws IOException, UnexpectedResponseException {
        params = new Params(params.getTlsConfig(), params.getSocketFactory(), params.getProxyHost(), params.getProxyPort(),
            null, null);
        return new MyTestClientAction().doTest(targetUri, params);
      }
    };
    boolean passed = false;
    try {
      SpecialHttpConfigurations.testHttpClientCanUseProxyWithBasicAuth(testActionHandler(),
          testActionThatIgnoresProxyAuthParams);
      passed = true;
    } catch (AssertionError e) {}
    assertFalse("expected test to fail", passed);
  }
  
  @Test
  public void testProxyAuthFailsWithWrongAuthProvided() {
    SpecialHttpConfigurations.TestAction testActionThatChangesProxyAuthParams = new SpecialHttpConfigurations.TestAction() {
      @Override
      public boolean doTest(URI targetUri, Params params) throws IOException, UnexpectedResponseException {
        params = new Params(params.getTlsConfig(), params.getSocketFactory(), params.getProxyHost(), params.getProxyPort(),
            params.getProxyBasicAuthUser(), "x" + params.getProxyBasicAuthPassword());
        return new MyTestClientAction().doTest(targetUri, params);
      }
    };
    boolean passed = false;
    try {
      SpecialHttpConfigurations.testHttpClientCanUseProxyWithBasicAuth(testActionHandler(),
          testActionThatChangesProxyAuthParams);
      passed = true;
    } catch (AssertionError e) {}
    assertFalse("expected test to fail", passed);
  }
}
