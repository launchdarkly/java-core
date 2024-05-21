package com.launchdarkly.sdk.internal.events;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.BaseTest;
import com.launchdarkly.sdk.internal.events.DiagnosticStore.SdkDiagnosticParams;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static com.launchdarkly.testhelpers.JsonAssertions.isJsonArray;
import static com.launchdarkly.testhelpers.JsonAssertions.jsonEqualsValue;
import static com.launchdarkly.testhelpers.JsonAssertions.jsonProperty;
import static com.launchdarkly.testhelpers.JsonAssertions.jsonUndefined;
import static com.launchdarkly.testhelpers.JsonTestValue.jsonFromValue;
import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertNotEquals;

@SuppressWarnings("javadoc")
public class DiagnosticStoreTest extends BaseTest {
  private static final String SDK_KEY = "key-abcdefg";
  private static final String SDK_NAME = "fake-sdk";
  private static final String SDK_VERSION = "1.2.3";
  private static final String PLATFORM_NAME = "fake-platform";
  
  @Test
  public void initEventBasicProperties() {
    long now = System.currentTimeMillis();
    DiagnosticStore store = makeSimpleStore();
    DiagnosticEvent ie = store.getInitEvent();
    assertThat(ie.initEvent, is(true));
    assertThat(ie.value.get("creationDate").longValue(), greaterThanOrEqualTo(now));
    assertThat(ie.value.get("id").get("diagnosticId"), not(equalTo(LDValue.ofNull())));
    assertThat(ie.value.get("id").get("sdkKeySuffix").stringValue(), equalTo("bcdefg"));
  }
  
  @Test
  public void initEventSdkData() {
    DiagnosticStore store = makeSimpleStore();
    DiagnosticEvent ie = store.getInitEvent();
    assertThat(jsonFromValue(ie.value),
        jsonProperty("sdk", allOf(
          jsonProperty("name", SDK_NAME),
          jsonProperty("version", SDK_VERSION),
          jsonProperty("wrapperName", jsonUndefined()),
          jsonProperty("wrapperVersion", jsonUndefined())
          )));
  }

  @Test
  public void initEventSdkDataWithWrapperName() {
    DiagnosticStore store = new DiagnosticStore(new SdkDiagnosticParams(
        SDK_KEY, SDK_NAME, SDK_VERSION, PLATFORM_NAME, null,
        singletonMap("X-LaunchDarkly-Wrapper", "Scala"),
        null
        ));
    DiagnosticEvent ie = store.getInitEvent();
    assertThat(jsonFromValue(ie.value),
        jsonProperty("sdk", allOf(
          jsonProperty("name", SDK_NAME),
          jsonProperty("version", SDK_VERSION),
          jsonProperty("wrapperName", "Scala"),
          jsonProperty("wrapperVersion", jsonUndefined())
          )));
  }

  @Test
  public void initEventSdkDataWithWrapperNameAndVersion() {
    DiagnosticStore store = new DiagnosticStore(new SdkDiagnosticParams(
        SDK_KEY, SDK_NAME, SDK_VERSION, PLATFORM_NAME, null,
        singletonMap("X-LaunchDarkly-Wrapper", "Scala/0.1"),
        null
        ));
    DiagnosticEvent ie = store.getInitEvent();
    assertThat(jsonFromValue(ie.value),
        jsonProperty("sdk", allOf(
          jsonProperty("name", SDK_NAME),
          jsonProperty("version", SDK_VERSION),
          jsonProperty("wrapperName", "Scala"),
          jsonProperty("wrapperVersion", "0.1")
          )));
  }
  
  @Test
  public void platformDataFromSdk() {
    DiagnosticStore store = new DiagnosticStore(new SdkDiagnosticParams(
        SDK_KEY, SDK_NAME, SDK_VERSION, PLATFORM_NAME,
        LDValue.buildObject().put("prop1", 2).put("prop2", 3).build(),
        null, null
        ));
    DiagnosticEvent ie = store.getInitEvent();
    assertThat(jsonFromValue(ie.value),
        jsonProperty("platform", allOf(
          jsonProperty("name", PLATFORM_NAME),
          jsonProperty("prop1", 2),
          jsonProperty("prop2", 3)
          )));
  }
  
  @Test
  public void configurationData() {
    List<LDValue> configValues = Arrays.asList(
        LDValue.buildObject()
          .put(DiagnosticConfigProperty.EVENTS_CAPACITY.name, 1000)
          .put(DiagnosticConfigProperty.USER_KEYS_CAPACITY.name, 2000)
          .put(DiagnosticConfigProperty.ALL_ATTRIBUTES_PRIVATE.name, "yes") // ignored because of wrong type
          .build(),
        LDValue.of("abcdef"), // ignored because it's not an object
        null, // no-op
        LDValue.buildObject().put(DiagnosticConfigProperty.DATA_STORE_TYPE.name, "custom").build()
        );
    DiagnosticStore store = new DiagnosticStore(new SdkDiagnosticParams(
        SDK_KEY, SDK_NAME, SDK_VERSION, PLATFORM_NAME, null, null,
        configValues
        ));
    DiagnosticEvent ie = store.getInitEvent();
    assertThat(jsonFromValue(ie.value),
        jsonProperty("configuration", jsonEqualsValue(
          LDValue.buildObject()
            .put(DiagnosticConfigProperty.EVENTS_CAPACITY.name, 1000)
            .put(DiagnosticConfigProperty.USER_KEYS_CAPACITY.name, 2000)
            .put(DiagnosticConfigProperty.DATA_STORE_TYPE.name, "custom")
            .build()
          )));
  }
  
  @Test
  public void createsDiagnosticStatisticsEvent() {
    DiagnosticStore store = makeSimpleStore();
    long startDate = store.getDataSinceDate();
    DiagnosticEvent statsEvent = store.createEventAndReset(10, 15);
    
    assertThat(jsonFromValue(statsEvent.value), allOf(
        jsonProperty("id", jsonProperty("diagnosticId", store.getDiagnosticId().diagnosticId)),
        jsonProperty("droppedEvents", 10),
        jsonProperty("deduplicatedUsers", 15),
        jsonProperty("eventsInLastBatch", 0),
        jsonProperty("dataSinceDate", startDate)
        ));
  }

  @Test
  public void canRecordStreamInit() {
    DiagnosticStore store = makeSimpleStore();
    store.recordStreamInit(1000, 200, false);
    DiagnosticEvent statsEvent = store.createEventAndReset(0, 0);
    
    assertThat(jsonFromValue(statsEvent.value),
        jsonProperty("streamInits", isJsonArray(
            contains(
                allOf(
                    jsonProperty("timestamp", 1000),
                    jsonProperty("durationMillis", 200),
                    jsonProperty("failed", false)
                    )
                )
            )));
  }

  @Test
  public void canRecordEventsInBatch() {
    DiagnosticStore store = makeSimpleStore();
    store.recordEventsInBatch(100);
    DiagnosticEvent statsEvent = store.createEventAndReset(0, 0);
    assertThat(jsonFromValue(statsEvent.value),
        jsonProperty("eventsInLastBatch", 100));
  }

  @Test
  public void resetsStatsOnCreate() throws InterruptedException {
    DiagnosticStore store = makeSimpleStore();
    store.recordStreamInit(1000, 200, false);
    store.recordEventsInBatch(100);
    long startDate = store.getDataSinceDate();
    Thread.sleep(2); // so that dataSinceDate will be different
    store.createEventAndReset(0, 0);
    assertNotEquals(startDate, store.getDataSinceDate());
    DiagnosticEvent statsEvent = store.createEventAndReset(0, 0);
    assertThat(jsonFromValue(statsEvent.value), allOf(
        jsonProperty("eventsInLastBatch", 0),
        jsonProperty("streamInits", isJsonArray(emptyIterable()))
        ));
  }
  
  private static DiagnosticStore makeSimpleStore() {
    return new DiagnosticStore(new SdkDiagnosticParams(SDK_KEY, SDK_NAME, SDK_VERSION, PLATFORM_NAME, null, null, null));
  }
}
