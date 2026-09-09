package com.launchdarkly.sdk.server;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.server.subsystems.DataSourceBuildInputs;

import org.junit.Test;

import java.util.concurrent.Executors;

import static com.launchdarkly.sdk.server.TestComponents.clientContext;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@SuppressWarnings({"javadoc", "deprecation"})
public class DataSystemComponentsTest extends BaseTest {
  private static final String WARNING_SUBSTRING = "Payload filtering is not supported";

  private DataSourceBuildInputs buildInputs() {
    return new DataSourceBuildInputs(
        testLogger,
        Thread.NORM_PRIORITY,
        null,
        Components.serviceEndpoints().createServiceEndpoints(),
        clientContext("sdk-key", baseConfig().build()).getHttp(),
        Executors.newSingleThreadScheduledExecutor(),
        null,
        () -> Selector.EMPTY);
  }

  private boolean loggedDeprecationWarning() {
    for (LogCapture.Message message : logCapture.getMessages()) {
      if (message.getLevel() == LDLogLevel.WARN && message.getText().contains(WARNING_SUBSTRING)) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void streamingSynchronizerWarnsWhenPayloadFilterIsConfigured() {
    DataSystemComponents.streamingSynchronizer().payloadFilter("microservice-1").build(buildInputs());
    assertTrue(loggedDeprecationWarning());
  }

  @Test
  public void pollingSynchronizerWarnsWhenPayloadFilterIsConfigured() {
    DataSystemComponents.pollingSynchronizer().payloadFilter("microservice-1").build(buildInputs());
    assertTrue(loggedDeprecationWarning());
  }

  @Test
  public void pollingInitializerWarnsWhenPayloadFilterIsConfigured() {
    DataSystemComponents.pollingInitializer().payloadFilter("microservice-1").build(buildInputs());
    assertTrue(loggedDeprecationWarning());
  }

  @Test
  public void noWarningWhenPayloadFilterIsNotConfigured() {
    DataSystemComponents.streamingSynchronizer().build(buildInputs());
    DataSystemComponents.pollingSynchronizer().build(buildInputs());
    DataSystemComponents.pollingInitializer().build(buildInputs());
    assertFalse(loggedDeprecationWarning());
  }
}
