package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.Components;
import com.launchdarkly.sdk.server.subsystems.PluginsConfiguration;
import org.junit.Test;

import java.util.Arrays;

import static org.easymock.EasyMock.mock;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class PluginsConfigurationBuilderTest {
  @Test
  public void emptyPluginsAsDefault() {
    PluginsConfiguration configuration = Components.plugins().build();
    assertEquals(0, configuration.getPlugins().size());
  }

  @Test
  public void canSetPlugins() {
    Plugin pluginA = mock(Plugin.class);
    Plugin pluginB = mock(Plugin.class);
    PluginsConfiguration configuration = Components.plugins().setPlugins(Arrays.asList(pluginA, pluginB)).build();
    assertEquals(2, configuration.getPlugins().size());
    assertSame(pluginA, configuration.getPlugins().get(0));
    assertSame(pluginB, configuration.getPlugins().get(1));
  }
}
