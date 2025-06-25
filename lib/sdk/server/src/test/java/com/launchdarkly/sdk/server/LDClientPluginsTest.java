package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.server.integrations.EnvironmentMetadata;
import com.launchdarkly.sdk.server.integrations.Hook;
import com.launchdarkly.sdk.server.integrations.Plugin;
import com.launchdarkly.sdk.server.integrations.PluginMetadata;
import com.launchdarkly.sdk.server.integrations.SdkMetadata;
import com.launchdarkly.sdk.server.interfaces.ApplicationInfo;

import org.junit.Test;
import org.easymock.IArgumentMatcher;

import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.mock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.reportMatcher;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Objects;

@SuppressWarnings("javadoc")
public class LDClientPluginsTest extends BaseTest {
  private static EnvironmentMetadata envMetaEquals(EnvironmentMetadata metadata) {
    reportMatcher(new IArgumentMatcher() {
      @Override
      public boolean matches(Object argument) {
        if (metadata == argument) {
          return true;
        }
        if (metadata == null || argument == null || !(argument instanceof EnvironmentMetadata)) {
          return false;
        }
        EnvironmentMetadata target = (EnvironmentMetadata)argument;
        return (
          Objects.equals(metadata.getSdkKey(), target.getSdkKey()) &&
          Objects.equals(metadata.getSdkMetadata().getName(), target.getSdkMetadata().getName()) &&
          Objects.equals(metadata.getSdkMetadata().getVersion(), target.getSdkMetadata().getVersion()) &&
          Objects.equals(metadata.getSdkMetadata().getWrapperName(), target.getSdkMetadata().getWrapperName()) &&
          Objects.equals(metadata.getSdkMetadata().getWrapperVersion(), target.getSdkMetadata().getWrapperVersion()) &&
          Objects.equals(metadata.getApplicationInfo().getApplicationId(), target.getApplicationInfo().getApplicationId()) &&
          Objects.equals(metadata.getApplicationInfo().getApplicationVersion(), target.getApplicationInfo().getApplicationVersion())
        );
      }
      @Override
      public void appendTo(StringBuffer buffer) {
        buffer.append("envMetaEquals()");
      }
    });
    return null;
  }

  private final static String SDK_KEY = "SDK_KEY";

  @Test
  public void pluginsAreProvidedEnvironmentMetadata() throws Exception {
    EnvironmentMetadata expected = new EnvironmentMetadata(
      new ApplicationInfo(null, null),
      new SdkMetadata("JavaClient", Version.SDK_VERSION),
      SDK_KEY
    );
    
    Plugin mockPlugin = mock(Plugin.class);
    expect(mockPlugin.getHooks(envMetaEquals(expected))).andReturn(Collections.emptyList());
    mockPlugin.register(isA(LDClient.class), envMetaEquals(expected));
    expectLastCall();
    replay(mockPlugin);

    LDConfig config = baseConfig()
      .plugins(Components.plugins().setPlugins(Collections.singletonList(mockPlugin)))
      .build();
    try (LDClient client = new LDClient(SDK_KEY, config)) {
      verify(mockPlugin);
    }
  }

  @Test
  public void pluginsAreProvidedEnvironmentMetadataWithApplicationInfo() throws Exception {
    EnvironmentMetadata expected = new EnvironmentMetadata(
      new ApplicationInfo("app-id", "app-version"),
      new SdkMetadata("JavaClient", Version.SDK_VERSION),
      SDK_KEY
    );
    
    Plugin mockPlugin = mock(Plugin.class);
    expect(mockPlugin.getHooks(envMetaEquals(expected))).andReturn(Collections.emptyList());
    mockPlugin.register(isA(LDClient.class), envMetaEquals(expected));
    expectLastCall();
    replay(mockPlugin);

    LDConfig config = baseConfig()
      .plugins(Components.plugins().setPlugins(Collections.singletonList(mockPlugin)))
      .applicationInfo(Components.applicationInfo().applicationId("app-id").applicationVersion("app-version"))
      .build();
    try (LDClient client = new LDClient(SDK_KEY, config)) {
      verify(mockPlugin);
    }
  }

  @Test
  public void pluginsAreProvidedEnvironmentMetadataWithWrapper() throws Exception {
    EnvironmentMetadata expected = new EnvironmentMetadata(
      new ApplicationInfo(null, null),
      new SdkMetadata("JavaClient", Version.SDK_VERSION, "wrapper-name", "wrapper-version"),
      SDK_KEY
    );
    
    Plugin mockPlugin = mock(Plugin.class);
    expect(mockPlugin.getHooks(envMetaEquals(expected))).andReturn(Collections.emptyList());
    mockPlugin.register(isA(LDClient.class), envMetaEquals(expected));
    expectLastCall();
    replay(mockPlugin);

    LDConfig config = baseConfig()
      .plugins(Components.plugins().setPlugins(Collections.singletonList(mockPlugin)))
      .wrapper(Components.wrapperInfo().wrapperName("wrapper-name").wrapperVersion("wrapper-version"))
      .build();
    try (LDClient client = new LDClient(SDK_KEY, config)) {
      verify(mockPlugin);
    }
  }

  @Test
  public void pluginHooksAreRegistered() throws Exception {
    Hook mockHook = mock(Hook.class);
    expect(mockHook.beforeEvaluation(anyObject(), anyObject())).andReturn(Collections.emptyMap());
    expect(mockHook.afterEvaluation(anyObject(), anyObject(), anyObject())).andReturn(Collections.emptyMap());
    
    Plugin mockPlugin = mock(Plugin.class);
    expect(mockPlugin.getHooks(anyObject())).andReturn(Collections.singletonList(mockHook));
    mockPlugin.register(anyObject(), anyObject());
    expectLastCall();

    replay(mockHook, mockPlugin);

    LDConfig config = baseConfig()
      .plugins(Components.plugins().setPlugins(Collections.singletonList(mockPlugin)))
      .build();
    try (LDClient client = new LDClient(SDK_KEY, config)) {
      client.boolVariation(SDK_KEY, LDContext.create("test-context"), false);
      verify(mockHook, mockPlugin);
    }
  }

  @Test
  public void handlesExceptionInGetHooks() throws Exception {
    Plugin mockPlugin = mock(Plugin.class);
    expect(mockPlugin.getHooks(anyObject())).andThrow(new RuntimeException("test error"));
    expect(mockPlugin.getMetadata()).andReturn(new PluginMetadata() { 
      @Override
      public String getName() {
        return "TestPlugin";
      }
    });
    mockPlugin.register(anyObject(), anyObject());
    expectLastCall();

    replay(mockPlugin);

    LDConfig config = baseConfig()
      .plugins(Components.plugins().setPlugins(Collections.singletonList(mockPlugin)))
      .build();

    try (LDClient client = new LDClient(SDK_KEY, config)) {
      assertTrue(
        logCapture.getMessageStrings().contains(
          "ERROR:Exception thrown getting hooks for plugin TestPlugin. Unable to get hooks, plugin will not be registered."
        )
      );
      verify(mockPlugin);
    }
  }

  @Test
  public void pluginHooksAreRegisteredWithExistingHooks() throws Exception {
    Hook mockExistingHook = mock(Hook.class);
    expect(mockExistingHook.beforeEvaluation(anyObject(), anyObject())).andReturn(Collections.emptyMap());
    expect(mockExistingHook.afterEvaluation(anyObject(), anyObject(), anyObject())).andReturn(Collections.emptyMap());

    Hook mockPluginHook = mock(Hook.class);
    expect(mockPluginHook.beforeEvaluation(anyObject(), anyObject())).andReturn(Collections.emptyMap());
    expect(mockPluginHook.afterEvaluation(anyObject(), anyObject(), anyObject())).andReturn(Collections.emptyMap());
    
    Plugin mockPlugin = mock(Plugin.class);
    expect(mockPlugin.getHooks(anyObject())).andReturn(Collections.singletonList(mockPluginHook));
    mockPlugin.register(anyObject(), anyObject());
    expectLastCall();

    replay(mockExistingHook, mockPluginHook, mockPlugin);

    LDConfig config = baseConfig()
      .hooks(Components.hooks().setHooks(Collections.singletonList(mockExistingHook)))
      .plugins(Components.plugins().setPlugins(Collections.singletonList(mockPlugin)))
      .build();
    try (LDClient client = new LDClient(SDK_KEY, config)) {
      client.boolVariation(SDK_KEY, LDContext.create("test-context"), false);
      verify(mockExistingHook, mockPluginHook, mockPlugin);
    }
  }

  @Test
  public void handlesExceptionInRegister() throws Exception {
    Plugin mockPlugin = mock(Plugin.class);
    expect(mockPlugin.getHooks(anyObject())).andReturn(Collections.emptyList());
    expect(mockPlugin.getMetadata()).andReturn(new PluginMetadata() { 
      @Override
      public String getName() {
        return "TestPlugin";
      }
    });
    mockPlugin.register(anyObject(), anyObject());
    expectLastCall().andThrow(new RuntimeException("test error"));

    replay(mockPlugin);

    LDConfig config = baseConfig()
      .plugins(Components.plugins().setPlugins(Collections.singletonList(mockPlugin)))
      .build();

    try (LDClient client = new LDClient(SDK_KEY, config)) {
      assertTrue(
        logCapture.getMessageStrings().contains(
          "ERROR:Exception thrown registering plugin TestPlugin. Plugin will not be registered."
        )
      );
      verify(mockPlugin);
    }
  }
}
