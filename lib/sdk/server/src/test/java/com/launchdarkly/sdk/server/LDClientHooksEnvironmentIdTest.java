package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.DataStoreTestTypes.DataBuilder;
import com.launchdarkly.sdk.server.integrations.EvaluationSeriesContext;
import com.launchdarkly.sdk.server.integrations.Hook;
import com.launchdarkly.sdk.server.integrations.MockPersistentDataStore;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider.CacheStats;
import com.launchdarkly.sdk.server.subsystems.DataStore;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.testhelpers.httptest.Handler;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;

import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.DataModel.SEGMENTS;
import static com.launchdarkly.sdk.server.ModelBuilders.flagBuilder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end tests verifying that the environment ID reported by LaunchDarkly reaches the
 * {@link EvaluationSeriesContext} given to hooks, for each way the SDK can receive flag data.
 */
@SuppressWarnings("javadoc")
public class LDClientHooksEnvironmentIdTest extends BaseTest {
  private static final String SDK_KEY = "sdk-key";
  private static final String ENV_ID_HEADER = "x-ld-envid";
  private static final String FLAG_KEY = "flag1";
  private static final LDContext CONTEXT = LDContext.create("user-key");
  private static final DataModel.FeatureFlag FLAG = flagBuilder(FLAG_KEY)
      .version(1).on(false).offVariation(0).variations(LDValue.of(true), LDValue.of(false))
      .build();

  /**
   * Records the environment ID seen at each stage of the evaluation series.
   */
  private static class RecordingHook extends Hook {
    final List<String> beforeEnvironmentIds = new CopyOnWriteArrayList<>();
    final List<String> afterEnvironmentIds = new CopyOnWriteArrayList<>();

    RecordingHook() {
      super("recording");
    }

    @Override
    public Map<String, Object> beforeEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> data) {
      beforeEnvironmentIds.add(String.valueOf(seriesContext.environmentId));
      return data;
    }

    @Override
    public Map<String, Object> afterEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> data,
        EvaluationDetail<LDValue> evaluationDetail) {
      afterEnvironmentIds.add(String.valueOf(seriesContext.environmentId));
      return data;
    }
  }

  private static String fdv1DataJson() {
    return new DataBuilder().addAny(FEATURES, FLAG).addAny(SEGMENTS).buildJson().toJsonString();
  }

  private static Handler fdv1StreamHandler(String environmentId) {
    return Handlers.all(
        Handlers.header(ENV_ID_HEADER, environmentId),
        Handlers.SSE.start(),
        Handlers.SSE.event("event: put\ndata: {\"data\":" + fdv1DataJson() + "}"),
        Handlers.SSE.leaveOpen());
  }

  private static Handler fdv1PollHandler(String environmentId) {
    return Handlers.all(Handlers.header(ENV_ID_HEADER, environmentId), Handlers.bodyJson(fdv1DataJson()));
  }

  private static String fdv2PutObjectJson() {
    return "{\"kind\":\"flag\",\"key\":\"" + FLAG_KEY + "\",\"version\":1,\"object\":" + JsonHelpers.serialize(FLAG) + "}";
  }

  private static Handler fdv2StreamHandler(String environmentId) {
    return Handlers.all(
        Handlers.header(ENV_ID_HEADER, environmentId),
        Handlers.SSE.start(),
        Handlers.SSE.event("event: server-intent\ndata: {\"payloads\":[{\"id\":\"payload-1\",\"target\":100,"
            + "\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}"),
        Handlers.SSE.event("event: put-object\ndata: " + fdv2PutObjectJson()),
        Handlers.SSE.event("event: payload-transferred\ndata: {\"state\":\"(p:payload-1:100)\",\"version\":100}"),
        Handlers.SSE.leaveOpen());
  }

  private static Handler fdv2PollHandler(String environmentId) {
    String body = "{\"events\":["
        + "{\"event\":\"server-intent\",\"data\":{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,"
        + "\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}},"
        + "{\"event\":\"put-object\",\"data\":" + fdv2PutObjectJson() + "},"
        + "{\"event\":\"payload-transferred\",\"data\":{\"state\":\"(p:payload-1:100)\",\"version\":100}}"
        + "]}";
    return Handlers.all(Handlers.header(ENV_ID_HEADER, environmentId), Handlers.bodyJson(body));
  }

  private LDConfig.Builder configWithHook(RecordingHook hook) {
    return baseConfig()
        .hooks(Components.hooks().setHooks(Collections.singletonList((Hook) hook)))
        .startWait(Duration.ofSeconds(10));
  }

  private static void assertHookSawEnvironmentId(RecordingHook hook, String expected) {
    assertEquals(ImmutableList.of(expected), hook.beforeEnvironmentIds);
    assertEquals(ImmutableList.of(expected), hook.afterEnvironmentIds);
  }

  @Test
  public void hookReceivesEnvironmentIdFromFDv1Stream() throws Exception {
    try (HttpServer server = HttpServer.start(fdv1StreamHandler("env-from-stream"))) {
      RecordingHook hook = new RecordingHook();
      LDConfig config = configWithHook(hook)
          .dataSource(Components.streamingDataSource())
          .serviceEndpoints(Components.serviceEndpoints().streaming(server.getUri()))
          .build();
      try (LDClient client = new LDClient(SDK_KEY, config)) {
        assertTrue(client.isInitialized());
        client.boolVariation(FLAG_KEY, CONTEXT, false);
        assertHookSawEnvironmentId(hook, "env-from-stream");
      }
    }
  }

  @Test
  public void hookReceivesEnvironmentIdFromFDv1Polling() throws Exception {
    try (HttpServer server = HttpServer.start(fdv1PollHandler("env-from-poll"))) {
      RecordingHook hook = new RecordingHook();
      LDConfig config = configWithHook(hook)
          .dataSource(Components.pollingDataSource().pollInterval(Duration.ofSeconds(300)))
          .serviceEndpoints(Components.serviceEndpoints().polling(server.getUri()))
          .build();
      try (LDClient client = new LDClient(SDK_KEY, config)) {
        assertTrue(client.isInitialized());
        client.boolVariation(FLAG_KEY, CONTEXT, false);
        assertHookSawEnvironmentId(hook, "env-from-poll");
      }
    }
  }

  @Test
  public void hookReceivesEnvironmentIdFromFDv2StreamingSynchronizer() throws Exception {
    try (HttpServer server = HttpServer.start(fdv2StreamHandler("env-from-fdv2-stream"))) {
      RecordingHook hook = new RecordingHook();
      LDConfig config = configWithHook(hook)
          .dataSystem(Components.dataSystem().custom()
              .synchronizers(DataSystemComponents.streamingSynchronizer()
                  .serviceEndpointsOverride(Components.serviceEndpoints().streaming(server.getUri()))))
          .build();
      try (LDClient client = new LDClient(SDK_KEY, config)) {
        assertTrue(client.isInitialized());
        client.boolVariation(FLAG_KEY, CONTEXT, false);
        assertHookSawEnvironmentId(hook, "env-from-fdv2-stream");
      }
    }
  }

  @Test
  public void hookReceivesEnvironmentIdFromFDv2PollingSynchronizer() throws Exception {
    try (HttpServer server = HttpServer.start(fdv2PollHandler("env-from-fdv2-poll"))) {
      RecordingHook hook = new RecordingHook();
      LDConfig config = configWithHook(hook)
          .dataSystem(Components.dataSystem().custom()
              .synchronizers(DataSystemComponents.pollingSynchronizer()
                  .pollInterval(Duration.ofSeconds(300))
                  .serviceEndpointsOverride(Components.serviceEndpoints().polling(server.getUri()))))
          .build();
      try (LDClient client = new LDClient(SDK_KEY, config)) {
        assertTrue(client.isInitialized());
        client.boolVariation(FLAG_KEY, CONTEXT, false);
        assertHookSawEnvironmentId(hook, "env-from-fdv2-poll");
      }
    }
  }

  @Test
  public void hookReceivesEnvironmentIdFromFDv2PollingInitializer() throws Exception {
    try (HttpServer server = HttpServer.start(fdv2PollHandler("env-from-fdv2-init"))) {
      RecordingHook hook = new RecordingHook();
      LDConfig config = configWithHook(hook)
          .dataSystem(Components.dataSystem().custom()
              .initializers(DataSystemComponents.pollingInitializer()
                  .serviceEndpointsOverride(Components.serviceEndpoints().polling(server.getUri())))
              .synchronizers(DataSystemComponents.pollingSynchronizer()
                  .pollInterval(Duration.ofSeconds(300))
                  .serviceEndpointsOverride(Components.serviceEndpoints().polling(server.getUri()))))
          .build();
      try (LDClient client = new LDClient(SDK_KEY, config)) {
        assertTrue(client.isInitialized());
        client.boolVariation(FLAG_KEY, CONTEXT, false);
        assertHookSawEnvironmentId(hook, "env-from-fdv2-init");
      }
    }
  }

  @Test
  public void hookDoesNotReceiveEnvironmentIdWhenPersistentStoreInitFails() throws Exception {
    // The environment ID is only known once data has been applied. With a finite cache TTL, a failed
    // write to the persistent store means nothing was applied.
    MockPersistentDataStore core = new MockPersistentDataStore();
    core.fakeError = new RuntimeException("store unavailable");
    try (HttpServer server = HttpServer.start(fdv1PollHandler("env-should-not-be-reported"))) {
      RecordingHook hook = new RecordingHook();
      LDConfig config = configWithHook(hook)
          .dataSource(Components.pollingDataSource().pollInterval(Duration.ofSeconds(300)))
          .dataStore(Components.persistentDataStore(ctx -> core).cacheTime(Duration.ofSeconds(30)))
          .serviceEndpoints(Components.serviceEndpoints().polling(server.getUri()))
          .startWait(Duration.ofMillis(500))
          .build();
      try (LDClient client = new LDClient(SDK_KEY, config)) {
        assertFalse(client.isInitialized());
        client.boolVariation(FLAG_KEY, CONTEXT, false);
        assertHookSawEnvironmentId(hook, "null");
      }
    }
  }

  @Test
  public void hookDoesNotReceiveEnvironmentIdFromDataStoreThatDoesNotProvideOne() throws Exception {
    // A custom DataStore that does not override getEnvironmentId() reports no environment ID, even
    // though the SDK received one and passed it to the store with the data.
    SimpleDataStore store = new SimpleDataStore();
    try (HttpServer server = HttpServer.start(fdv1PollHandler("env-from-poll"))) {
      RecordingHook hook = new RecordingHook();
      LDConfig config = configWithHook(hook)
          .dataSource(Components.pollingDataSource().pollInterval(Duration.ofSeconds(300)))
          .dataStore(ctx -> store)
          .serviceEndpoints(Components.serviceEndpoints().polling(server.getUri()))
          .build();
      try (LDClient client = new LDClient(SDK_KEY, config)) {
        assertTrue(client.isInitialized());
        client.boolVariation(FLAG_KEY, CONTEXT, false);
        assertEquals("env-from-poll", store.lastInit.getEnvironmentId());
        assertHookSawEnvironmentId(hook, "null");
      }
    }
  }

  @Test
  public void evaluationSucceedsWhenDataStoreEnvironmentIdThrows() throws Exception {
    // A failure while looking up the environment ID must not turn into an exception from the
    // variation methods, and must not prevent the hooks from running.
    SimpleDataStore store = new SimpleDataStore() {
      @Override
      public String getEnvironmentId() {
        throw new IllegalStateException("environment ID unavailable");
      }
    };
    try (HttpServer server = HttpServer.start(fdv1PollHandler("env-from-poll"))) {
      RecordingHook hook = new RecordingHook();
      LDConfig config = configWithHook(hook)
          .dataSource(Components.pollingDataSource().pollInterval(Duration.ofSeconds(300)))
          .dataStore(ctx -> store)
          .serviceEndpoints(Components.serviceEndpoints().polling(server.getUri()))
          .build();
      try (LDClient client = new LDClient(SDK_KEY, config)) {
        assertTrue(client.isInitialized());
        assertTrue(client.boolVariation(FLAG_KEY, CONTEXT, false));
        assertHookSawEnvironmentId(hook, "null");
      }
    }
  }

  /**
   * A minimal DataStore implementation that relies on the default getEnvironmentId().
   */
  private static class SimpleDataStore implements DataStore {
    private final Map<DataKind, Map<String, ItemDescriptor>> data = new HashMap<>();
    volatile FullDataSet<ItemDescriptor> lastInit;
    volatile boolean initialized;

    @Override
    public void init(FullDataSet<ItemDescriptor> allData) {
      lastInit = allData;
      data.clear();
      for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> kindEntry : allData.getData()) {
        Map<String, ItemDescriptor> items = new HashMap<>();
        for (Map.Entry<String, ItemDescriptor> itemEntry : kindEntry.getValue().getItems()) {
          items.put(itemEntry.getKey(), itemEntry.getValue());
        }
        data.put(kindEntry.getKey(), items);
      }
      initialized = true;
    }

    @Override
    public ItemDescriptor get(DataKind kind, String key) {
      Map<String, ItemDescriptor> items = data.get(kind);
      return items == null ? null : items.get(key);
    }

    @Override
    public KeyedItems<ItemDescriptor> getAll(DataKind kind) {
      Map<String, ItemDescriptor> items = data.get(kind);
      return items == null ? new KeyedItems<>(null) : new KeyedItems<>(ImmutableList.copyOf(items.entrySet()));
    }

    @Override
    public boolean upsert(DataKind kind, String key, ItemDescriptor item) {
      data.computeIfAbsent(kind, k -> new HashMap<>()).put(key, item);
      return true;
    }

    @Override
    public boolean isInitialized() {
      return initialized;
    }

    @Override
    public boolean isStatusMonitoringEnabled() {
      return false;
    }

    @Override
    public CacheStats getCacheStats() {
      return null;
    }

    @Override
    public void close() throws IOException {
    }
  }
}
