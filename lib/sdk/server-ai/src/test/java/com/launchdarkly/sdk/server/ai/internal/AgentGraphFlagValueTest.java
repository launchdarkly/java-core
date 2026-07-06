package com.launchdarkly.sdk.server.ai.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.ai.GraphEdge;

import java.util.List;
import java.util.Map;

import org.junit.Test;

@SuppressWarnings("javadoc")
public class AgentGraphFlagValueTest {

  // ---- disabled() factory ---------------------------------------------------

  @Test
  public void disabledReturnsEnabledFalse() {
    AgentGraphFlagValue v = AgentGraphFlagValue.disabled();
    assertThat(v.isEnabled(), is(false));
    assertThat(v.getRoot(), is(""));
    assertThat(v.getEdges().isEmpty(), is(true));
    assertThat(v.getVariationKey(), is(nullValue()));
    assertThat(v.getVersion(), is(1));
  }

  // ---- parse: non-object input ----------------------------------------------

  @Test
  public void parseNullReturnsDisabled() {
    AgentGraphFlagValue v = AgentGraphFlagValue.parse(null);
    assertThat(v.isEnabled(), is(false));
  }

  @Test
  public void parseStringReturnsDisabled() {
    AgentGraphFlagValue v = AgentGraphFlagValue.parse(LDValue.of("not-an-object"));
    assertThat(v.isEnabled(), is(false));
  }

  @Test
  public void parseArrayReturnsDisabled() {
    AgentGraphFlagValue v = AgentGraphFlagValue.parse(LDValue.buildArray().add("x").build());
    assertThat(v.isEnabled(), is(false));
  }

  // ---- parse: defaults when fields absent ----------------------------------

  @Test
  public void parseEmptyObjectUsesDefaults() {
    AgentGraphFlagValue v = AgentGraphFlagValue.parse(LDValue.buildObject().build());
    assertThat(v.isEnabled(), is(true));      // default true for graphs
    assertThat(v.getVersion(), is(1));         // default 1
    assertThat(v.getRoot(), is(""));           // empty string default
    assertThat(v.getEdges().isEmpty(), is(true));
    assertThat(v.getVariationKey(), is(nullValue()));
  }

  // ---- parse: _ldMeta fields -----------------------------------------------

  @Test
  public void parsesEnabledFalseFromMeta() {
    LDValue value = LDValue.buildObject()
        .put("root", "node-a")
        .put("_ldMeta", LDValue.buildObject()
            .put("enabled", false)
            .build())
        .build();
    AgentGraphFlagValue v = AgentGraphFlagValue.parse(value);
    assertThat(v.isEnabled(), is(false));
  }

  @Test
  public void parsesEnabledTrueFromMeta() {
    LDValue value = LDValue.buildObject()
        .put("root", "node-a")
        .put("_ldMeta", LDValue.buildObject()
            .put("enabled", true)
            .build())
        .build();
    AgentGraphFlagValue v = AgentGraphFlagValue.parse(value);
    assertThat(v.isEnabled(), is(true));
  }

  @Test
  public void parsesVariationKeyFromMeta() {
    LDValue value = LDValue.buildObject()
        .put("root", "node-a")
        .put("_ldMeta", LDValue.buildObject()
            .put("variationKey", "var-xyz")
            .build())
        .build();
    AgentGraphFlagValue v = AgentGraphFlagValue.parse(value);
    assertThat(v.getVariationKey(), is("var-xyz"));
  }

  @Test
  public void parsesVersionFromMeta() {
    LDValue value = LDValue.buildObject()
        .put("root", "node-a")
        .put("_ldMeta", LDValue.buildObject()
            .put("version", 5)
            .build())
        .build();
    AgentGraphFlagValue v = AgentGraphFlagValue.parse(value);
    assertThat(v.getVersion(), is(5));
  }

  @Test
  public void missingMetaVersionDefaultsToOne() {
    LDValue value = LDValue.buildObject()
        .put("root", "node-a")
        .put("_ldMeta", LDValue.buildObject().build())
        .build();
    AgentGraphFlagValue v = AgentGraphFlagValue.parse(value);
    assertThat(v.getVersion(), is(1));
  }

  @Test
  public void nonObjectMetaIsIgnored() {
    LDValue value = LDValue.buildObject()
        .put("root", "node-a")
        .put("_ldMeta", LDValue.of("not-an-object"))
        .build();
    AgentGraphFlagValue v = AgentGraphFlagValue.parse(value);
    assertThat(v.isEnabled(), is(true)); // fallback to default
    assertThat(v.getVersion(), is(1));
  }

  // ---- parse: root ---------------------------------------------------------

  @Test
  public void parsesRootString() {
    LDValue value = LDValue.buildObject()
        .put("root", "entry-node")
        .build();
    AgentGraphFlagValue v = AgentGraphFlagValue.parse(value);
    assertThat(v.getRoot(), is("entry-node"));
  }

  @Test
  public void missingRootIsEmptyString() {
    AgentGraphFlagValue v = AgentGraphFlagValue.parse(LDValue.buildObject().build());
    assertThat(v.getRoot(), is(""));
  }

  @Test
  public void nonStringRootIsIgnored() {
    LDValue value = LDValue.buildObject()
        .put("root", 42)
        .build();
    AgentGraphFlagValue v = AgentGraphFlagValue.parse(value);
    assertThat(v.getRoot(), is(""));
  }

  // ---- parse: edges --------------------------------------------------------

  @Test
  public void parsesEdgesMap() {
    LDValue value = LDValue.buildObject()
        .put("root", "node-a")
        .put("edges", LDValue.buildObject()
            .put("node-a", LDValue.buildArray()
                .add(LDValue.buildObject().put("key", "node-b").build())
                .build())
            .build())
        .build();

    AgentGraphFlagValue v = AgentGraphFlagValue.parse(value);
    Map<String, List<GraphEdge>> edges = v.getEdges();
    assertThat(edges.containsKey("node-a"), is(true));
    List<GraphEdge> aEdges = edges.get("node-a");
    assertThat(aEdges.size(), is(1));
    assertThat(aEdges.get(0).getKey(), is("node-b"));
    assertThat(aEdges.get(0).getHandoff(), is(nullValue()));
  }

  @Test
  public void parsesEdgeWithHandoff() {
    LDValue value = LDValue.buildObject()
        .put("root", "node-a")
        .put("edges", LDValue.buildObject()
            .put("node-a", LDValue.buildArray()
                .add(LDValue.buildObject()
                    .put("key", "node-b")
                    .put("handoff", LDValue.buildObject()
                        .put("someData", LDValue.of("hello"))
                        .build())
                    .build())
                .build())
            .build())
        .build();

    AgentGraphFlagValue v = AgentGraphFlagValue.parse(value);
    GraphEdge edge = v.getEdges().get("node-a").get(0);
    assertThat(edge.getKey(), is("node-b"));
    assertThat(edge.getHandoff(), is(notNullValue()));
    assertThat(edge.getHandoff().get("someData").stringValue(), is("hello"));
  }

  @Test
  public void edgeMissingKeyIsSkipped() {
    LDValue value = LDValue.buildObject()
        .put("root", "node-a")
        .put("edges", LDValue.buildObject()
            .put("node-a", LDValue.buildArray()
                .add(LDValue.buildObject().put("notKey", "node-b").build())
                .build())
            .build())
        .build();

    AgentGraphFlagValue v = AgentGraphFlagValue.parse(value);
    assertThat(v.getEdges().get("node-a"), is(empty()));
  }

  @Test
  public void edgeWithNonArrayValueIsSkipped() {
    LDValue value = LDValue.buildObject()
        .put("root", "node-a")
        .put("edges", LDValue.buildObject()
            .put("node-a", LDValue.of("not-an-array"))
            .build())
        .build();

    AgentGraphFlagValue v = AgentGraphFlagValue.parse(value);
    assertThat(v.getEdges().containsKey("node-a"), is(false));
  }

  @Test
  public void nonObjectEdgesFieldIsIgnored() {
    LDValue value = LDValue.buildObject()
        .put("root", "node-a")
        .put("edges", LDValue.of("bad"))
        .build();

    AgentGraphFlagValue v = AgentGraphFlagValue.parse(value);
    assertThat(v.getEdges().isEmpty(), is(true));
  }

  // ---- parse: full round-trip ----------------------------------------------

  @Test
  public void parsesFullFlagValue() {
    LDValue value = LDValue.buildObject()
        .put("root", "node-a")
        .put("edges", LDValue.buildObject()
            .put("node-a", LDValue.buildArray()
                .add(LDValue.buildObject().put("key", "node-b").build())
                .add(LDValue.buildObject().put("key", "node-c").build())
                .build())
            .put("node-b", LDValue.buildArray()
                .add(LDValue.buildObject().put("key", "node-c").build())
                .build())
            .build())
        .put("_ldMeta", LDValue.buildObject()
            .put("enabled", true)
            .put("variationKey", "var-1")
            .put("version", 2)
            .build())
        .build();

    AgentGraphFlagValue v = AgentGraphFlagValue.parse(value);
    assertThat(v.isEnabled(), is(true));
    assertThat(v.getRoot(), is("node-a"));
    assertThat(v.getVariationKey(), is("var-1"));
    assertThat(v.getVersion(), is(2));
    assertThat(v.getEdges().get("node-a").size(), is(2));
    assertThat(v.getEdges().get("node-b").size(), is(1));
  }
}
