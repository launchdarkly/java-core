package com.launchdarkly.sdk.server.ai;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;

import com.launchdarkly.sdk.ArrayBuilder;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.server.ai.internal.AgentGraphFlagValue;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.junit.Test;

@SuppressWarnings("javadoc")
public class AgentGraphDefinitionTest {

  // ---- helpers --------------------------------------------------------------

  private static AgentGraphFlagValue flagValue(String root, String[][] edges) {
    ObjectBuilder edgesObj = LDValue.buildObject();
    if (edges != null) {
      Map<String, List<String>> adj = new LinkedHashMap<>();
      for (String[] edge : edges) {
        if (!adj.containsKey(edge[0])) {
          adj.put(edge[0], new ArrayList<>());
        }
        adj.get(edge[0]).add(edge[1]);
      }
      for (Map.Entry<String, List<String>> entry : adj.entrySet()) {
        ArrayBuilder arr = LDValue.buildArray();
        for (String target : entry.getValue()) {
          arr.add(LDValue.buildObject().put("key", target).build());
        }
        edgesObj.put(entry.getKey(), arr.build());
      }
    }
    LDValue value = LDValue.buildObject()
        .put("root", root)
        .put("edges", edgesObj.build())
        .put("_ldMeta", LDValue.buildObject()
            .put("enabled", true)
            .put("version", 1)
            .build())
        .build();
    return AgentGraphFlagValue.parse(value);
  }

  private static AIAgentConfig makeConfig(String key, boolean enabled) {
    return new AIAgentConfig(key, enabled, null, null, null, null, null,
        () -> mock(com.launchdarkly.sdk.server.ai.LDAIConfigTracker.class),
        Evaluator.noop());
  }

  private static Map<String, AIAgentConfig> configs(String... keys) {
    Map<String, AIAgentConfig> m = new HashMap<>();
    for (String key : keys) {
      m.put(key, makeConfig(key, true));
    }
    return m;
  }

  private AgentGraphDefinition buildEnabled(String root, String[][] edges, String... nodeKeys) {
    AgentGraphFlagValue fv = flagValue(root, edges);
    Map<String, AgentGraphNode> nodes = AgentGraphDefinition.buildNodes(fv, configs(nodeKeys));
    return new AgentGraphDefinition(fv, nodes, true, null);
  }

  // ---- collectAllKeys -------------------------------------------------------

  @Test
  public void collectAllKeysIncludesRoot() {
    AgentGraphFlagValue fv = flagValue("root-node", null);
    Set<String> keys = AgentGraphDefinition.collectAllKeys(fv);
    assertThat(keys.contains("root-node"), is(true));
  }

  @Test
  public void collectAllKeysIncludesEdgeSourcesAndTargets() {
    AgentGraphFlagValue fv = flagValue("a", new String[][]{{"a", "b"}, {"b", "c"}});
    Set<String> keys = AgentGraphDefinition.collectAllKeys(fv);
    assertThat(keys, containsInAnyOrder("a", "b", "c"));
  }

  @Test
  public void collectAllKeysWithNoEdges() {
    AgentGraphFlagValue fv = flagValue("solo", null);
    Set<String> keys = AgentGraphDefinition.collectAllKeys(fv);
    assertThat(keys, containsInAnyOrder("solo"));
  }

  @Test
  public void collectAllKeysEmptyRootIsExcluded() {
    AgentGraphFlagValue fv = AgentGraphFlagValue.disabled();
    Set<String> keys = AgentGraphDefinition.collectAllKeys(fv);
    assertThat(keys, is(empty()));
  }

  // ---- buildNodes -----------------------------------------------------------

  @Test
  public void buildNodesCreatesCorrectNodeMap() {
    AgentGraphFlagValue fv = flagValue("a", new String[][]{{"a", "b"}});
    Map<String, AgentGraphNode> nodes = AgentGraphDefinition.buildNodes(fv, configs("a", "b"));
    assertThat(nodes.size(), is(2));
    assertThat(nodes.get("a").getKey(), is("a"));
    assertThat(nodes.get("b").getKey(), is("b"));
  }

  @Test
  public void buildNodesAttachesEdgesToNodes() {
    AgentGraphFlagValue fv = flagValue("a", new String[][]{{"a", "b"}, {"a", "c"}});
    Map<String, AgentGraphNode> nodes = AgentGraphDefinition.buildNodes(fv, configs("a", "b", "c"));
    List<GraphEdge> edges = nodes.get("a").getEdges();
    assertThat(edges.size(), is(2));
  }

  @Test
  public void buildNodesSkipsMissingConfigs() {
    AgentGraphFlagValue fv = flagValue("a", new String[][]{{"a", "b"}});
    // Only provide config for "a", not "b"
    Map<String, AgentGraphNode> nodes = AgentGraphDefinition.buildNodes(fv, configs("a"));
    assertThat(nodes.size(), is(1));
    assertThat(nodes.containsKey("a"), is(true));
    assertThat(nodes.containsKey("b"), is(false));
  }

  // ---- rootNode / getNode --------------------------------------------------

  @Test
  public void rootNodeReturnsCorrectNode() {
    AgentGraphDefinition graph = buildEnabled("a", new String[][]{{"a", "b"}}, "a", "b");
    AgentGraphNode root = graph.rootNode();
    assertThat(root, is(notNullValue()));
    assertThat(root.getKey(), is("a"));
  }

  @Test
  public void rootNodeReturnsNullWhenDisabled() {
    AgentGraphDefinition graph = new AgentGraphDefinition(
        AgentGraphFlagValue.disabled(), Collections.emptyMap(), false, null);
    assertThat(graph.rootNode(), is(nullValue()));
  }

  @Test
  public void getNodeReturnsCorrectNode() {
    AgentGraphDefinition graph = buildEnabled("a", new String[][]{{"a", "b"}}, "a", "b");
    assertThat(graph.getNode("b").getKey(), is("b"));
  }

  @Test
  public void getNodeReturnsNullForUnknownKey() {
    AgentGraphDefinition graph = buildEnabled("a", null, "a");
    assertThat(graph.getNode("not-here"), is(nullValue()));
  }

  // ---- isTerminal ----------------------------------------------------------

  @Test
  public void terminalNodeHasNoEdges() {
    AgentGraphDefinition graph = buildEnabled("a", new String[][]{{"a", "b"}}, "a", "b");
    assertThat(graph.getNode("b").isTerminal(), is(true));
    assertThat(graph.getNode("a").isTerminal(), is(false));
  }

  @Test
  public void singleNodeGraphIsTerminal() {
    AgentGraphDefinition graph = buildEnabled("a", null, "a");
    assertThat(graph.rootNode().isTerminal(), is(true));
  }

  // ---- getChildNodes -------------------------------------------------------

  @Test
  public void getChildNodesFollowsEdges() {
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"a", "c"}}, "a", "b", "c");
    List<AgentGraphNode> children = graph.getChildNodes("a");
    assertThat(children.size(), is(2));
    Set<String> keys = new HashSet<>();
    for (AgentGraphNode n : children) keys.add(n.getKey());
    assertThat(keys, containsInAnyOrder("b", "c"));
  }

  @Test
  public void getChildNodesReturnsEmptyForTerminal() {
    AgentGraphDefinition graph = buildEnabled("a", new String[][]{{"a", "b"}}, "a", "b");
    assertThat(graph.getChildNodes("b"), is(empty()));
  }

  @Test
  public void getChildNodesReturnsEmptyForUnknownKey() {
    AgentGraphDefinition graph = buildEnabled("a", null, "a");
    assertThat(graph.getChildNodes("no-such-key"), is(empty()));
  }

  // ---- getParentNodes ------------------------------------------------------

  @Test
  public void getParentNodesFindsDirectParents() {
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "c"}, {"b", "c"}}, "a", "b", "c");
    List<AgentGraphNode> parents = graph.getParentNodes("c");
    assertThat(parents.size(), is(2));
    Set<String> keys = new HashSet<>();
    for (AgentGraphNode n : parents) keys.add(n.getKey());
    assertThat(keys, containsInAnyOrder("a", "b"));
  }

  @Test
  public void getParentNodesReturnsEmptyForRoot() {
    AgentGraphDefinition graph = buildEnabled("a", new String[][]{{"a", "b"}}, "a", "b");
    assertThat(graph.getParentNodes("a"), is(empty()));
  }

  // ---- terminalNodes -------------------------------------------------------

  @Test
  public void terminalNodesReturnsAllTerminals() {
    // a -> b, a -> c; b and c are terminals
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"a", "c"}}, "a", "b", "c");
    List<AgentGraphNode> terminals = graph.terminalNodes();
    assertThat(terminals.size(), is(2));
    Set<String> keys = new HashSet<>();
    for (AgentGraphNode n : terminals) keys.add(n.getKey());
    assertThat(keys, containsInAnyOrder("b", "c"));
  }

  @Test
  public void terminalNodesWithSingleNodeIncludesRoot() {
    AgentGraphDefinition graph = buildEnabled("a", null, "a");
    assertThat(graph.terminalNodes().size(), is(1));
    assertThat(graph.terminalNodes().get(0).getKey(), is("a"));
  }

  // ---- isEnabled -----------------------------------------------------------

  @Test
  public void isEnabledReflectsConstructorValue() {
    AgentGraphDefinition enabled = buildEnabled("a", null, "a");
    assertThat(enabled.isEnabled(), is(true));

    AgentGraphDefinition disabled = new AgentGraphDefinition(
        AgentGraphFlagValue.disabled(), Collections.emptyMap(), false, null);
    assertThat(disabled.isEnabled(), is(false));
  }

  // ---- createTracker -------------------------------------------------------

  @Test
  public void createTrackerReturnsNullWhenDisabled() {
    // A null factory is the only case that returns null (defensive guard).
    AgentGraphDefinition graph = new AgentGraphDefinition(
        AgentGraphFlagValue.disabled(), Collections.emptyMap(), false, null);
    assertThat(graph.createTracker(), is(nullValue()));
  }

  @Test
  public void createTrackerReturnsTrackerEvenWhenDisabled() {
    // Disabled graphs still produce a tracker so callers can fire graph-level usage events
    // (e.g. invocation failure) when the graph's configuration could not be resolved.
    LDClientInterface client = mock(LDClientInterface.class);
    AgentGraphDefinition graph = new AgentGraphDefinition(
        AgentGraphFlagValue.disabled(), Collections.emptyMap(), false,
        () -> new AIGraphTracker(client, "run-id", "graph-key", null, 1,
            com.launchdarkly.sdk.LDContext.create("user"),
            com.launchdarkly.logging.LDLogger.withAdapter(
                com.launchdarkly.logging.Logs.none(), "")));
    assertThat(graph.createTracker(), is(notNullValue()));
  }

  @Test
  public void createTrackerReturnsTrackerWhenEnabled() {
    LDClientInterface client = mock(LDClientInterface.class);
    AgentGraphFlagValue fv = flagValue("a", null);
    Map<String, AgentGraphNode> nodes = AgentGraphDefinition.buildNodes(fv, configs("a"));
    AgentGraphDefinition graph = new AgentGraphDefinition(fv, nodes, true,
        () -> new AIGraphTracker(client, "run-id", "graph-key", null, 1,
            com.launchdarkly.sdk.LDContext.create("user"),
            com.launchdarkly.logging.LDLogger.withAdapter(
                com.launchdarkly.logging.Logs.none(), "")));
    assertThat(graph.createTracker(), is(notNullValue()));
  }

  // ---- traverse -------------------------------------------------------------

  @Test
  public void traverseVisitsAllNodesFromRoot() {
    // a -> b -> c
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"b", "c"}}, "a", "b", "c");

    List<String> visited = new ArrayList<>();
    BiFunction<AgentGraphNode, Map<String, Object>, Object> fn = (node, ctx) -> {
      visited.add(node.getKey());
      return node.getKey();
    };
    graph.traverse(fn, new HashMap<>());
    assertThat(visited, containsInAnyOrder("a", "b", "c"));
    // Root must be first
    assertThat(visited.get(0), is("a"));
  }

  @Test
  public void traverseStoresResultsInContext() {
    AgentGraphDefinition graph = buildEnabled("a", new String[][]{{"a", "b"}}, "a", "b");

    Map<String, Object> ctx = new HashMap<>();
    BiFunction<AgentGraphNode, Map<String, Object>, Object> fn = (node, c) -> node.getKey() + "_result";
    graph.traverse(fn, ctx);

    assertThat(ctx.get("a"), is("a_result"));
    assertThat(ctx.get("b"), is("b_result"));
  }

  @Test
  public void traverseIsNoOpWhenDisabled() {
    AgentGraphDefinition graph = new AgentGraphDefinition(
        AgentGraphFlagValue.disabled(), Collections.emptyMap(), false, null);

    List<String> visited = new ArrayList<>();
    graph.traverse((node, ctx) -> { visited.add(node.getKey()); return null; }, new HashMap<>());
    assertThat(visited, is(empty()));
  }

  @Test
  public void traverseHandlesCyclesSafely() {
    // Manually build a cyclic graph: a -> b -> a
    LDClientInterface client = mock(LDClientInterface.class);
    Map<String, AIAgentConfig> cfgs = configs("a", "b");
    List<GraphEdge> aEdges = Collections.singletonList(new GraphEdge("b", null));
    List<GraphEdge> bEdges = Collections.singletonList(new GraphEdge("a", null));
    Map<String, AgentGraphNode> nodes = new HashMap<>();
    nodes.put("a", new AgentGraphNode("a", cfgs.get("a"), aEdges));
    nodes.put("b", new AgentGraphNode("b", cfgs.get("b"), bEdges));
    nodes = Collections.unmodifiableMap(nodes);

    AgentGraphFlagValue fv = flagValue("a", new String[][]{{"a", "b"}, {"b", "a"}});
    AgentGraphDefinition graph = new AgentGraphDefinition(fv, nodes, true, null);

    List<String> visited = new ArrayList<>();
    graph.traverse((node, ctx) -> { visited.add(node.getKey()); return null; }, new HashMap<>());
    assertThat(visited.size(), is(2)); // each node visited exactly once
  }

  // ---- reverseTraverse ------------------------------------------------------

  @Test
  public void reverseTraverseProcessesRootLast() {
    // a -> b -> c
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"b", "c"}}, "a", "b", "c");

    List<String> visited = new ArrayList<>();
    graph.reverseTraverse((node, ctx) -> { visited.add(node.getKey()); return null; }, new HashMap<>());

    // c is terminal (seeded first), root "a" is last
    assertThat(visited.get(visited.size() - 1), is("a"));
    assertThat(visited.contains("b"), is(true));
    assertThat(visited.contains("c"), is(true));
  }

  @Test
  public void reverseTraverseVisitsAllNodes() {
    // a -> b, a -> c (c and b are terminals)
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"a", "c"}}, "a", "b", "c");

    List<String> visited = new ArrayList<>();
    graph.reverseTraverse((node, ctx) -> { visited.add(node.getKey()); return null; }, new HashMap<>());
    assertThat(visited, containsInAnyOrder("a", "b", "c"));
    assertThat(visited.get(visited.size() - 1), is("a"));
  }

  @Test
  public void reverseTraverseSingleNodeGraph() {
    AgentGraphDefinition graph = buildEnabled("a", null, "a");

    List<String> visited = new ArrayList<>();
    graph.reverseTraverse((node, ctx) -> { visited.add(node.getKey()); return null; }, new HashMap<>());
    assertThat(visited, containsInAnyOrder("a"));
  }

  @Test
  public void reverseTraverseHandlesCyclesSafely() {
    Map<String, AIAgentConfig> cfgs = configs("a", "b");
    List<GraphEdge> aEdges = Collections.singletonList(new GraphEdge("b", null));
    List<GraphEdge> bEdges = Collections.singletonList(new GraphEdge("a", null));
    Map<String, AgentGraphNode> nodes = new HashMap<>();
    nodes.put("a", new AgentGraphNode("a", cfgs.get("a"), aEdges));
    nodes.put("b", new AgentGraphNode("b", cfgs.get("b"), bEdges));
    nodes = Collections.unmodifiableMap(nodes);

    AgentGraphFlagValue fv = flagValue("a", new String[][]{{"a", "b"}, {"b", "a"}});
    AgentGraphDefinition graph = new AgentGraphDefinition(fv, nodes, true, null);

    List<String> visited = new ArrayList<>();
    graph.reverseTraverse((node, ctx) -> { visited.add(node.getKey()); return null; }, new HashMap<>());
    // No infinite loop; in a pure cycle neither node is terminal, so no seeds are added —
    // only root is processed in the final "root last" block.
    assertThat(visited.size() <= 2, is(true));
    assertThat(visited.size() >= 1, is(true));
  }

  @Test
  public void reverseTraverseIsNoOpWhenDisabled() {
    AgentGraphDefinition graph = new AgentGraphDefinition(
        AgentGraphFlagValue.disabled(), Collections.emptyMap(), false, null);

    List<String> visited = new ArrayList<>();
    graph.reverseTraverse((node, ctx) -> { visited.add(node.getKey()); return null; }, new HashMap<>());
    assertThat(visited, is(empty()));
  }

  // ---- diamond graph traversal ----------------------------------------------

  @Test
  public void traverseDiamondGraph() {
    // root -> a, root -> b; a -> sink, b -> sink
    AgentGraphDefinition graph = buildEnabled("root",
        new String[][]{{"root", "a"}, {"root", "b"}, {"a", "sink"}, {"b", "sink"}},
        "root", "a", "b", "sink");

    List<String> visited = new ArrayList<>();
    graph.traverse((node, ctx) -> { visited.add(node.getKey()); return null; }, new HashMap<>());
    // root first, sink visited only once
    assertThat(visited.get(0), is("root"));
    assertThat(visited.size(), is(4));
    assertThat(new HashSet<>(visited).size(), is(4)); // all unique
  }

  @Test
  public void reverseTraverseDiamondGraph() {
    AgentGraphDefinition graph = buildEnabled("root",
        new String[][]{{"root", "a"}, {"root", "b"}, {"a", "sink"}, {"b", "sink"}},
        "root", "a", "b", "sink");

    List<String> visited = new ArrayList<>();
    graph.reverseTraverse((node, ctx) -> { visited.add(node.getKey()); return null; }, new HashMap<>());
    // root last, sink visited once
    assertThat(visited.get(visited.size() - 1), is("root"));
    assertThat(visited.size(), is(4));
    assertThat(new HashSet<>(visited).size(), is(4));
  }
}
