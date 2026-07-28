package com.launchdarkly.sdk.server.ai;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
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

  // ---- traversal helpers ----------------------------------------------------

  private static List<String> visitOrder(
      AgentGraphDefinition graph, boolean reverse, Map<String, Object> initialCtx) {
    List<String> visited = new ArrayList<>();
    BiFunction<AgentGraphNode, Map<String, Object>, Object> fn = (node, ctx) -> {
      visited.add(node.getKey());
      return node.getKey() + "_result";
    };
    if (reverse) {
      graph.reverseTraverse(fn, initialCtx);
    } else {
      graph.traverse(fn, initialCtx);
    }
    return visited;
  }

  private static Map<String, Set<String>> captureContextKeys(
      AgentGraphDefinition graph, boolean reverse, Map<String, Object> initialCtx) {
    Map<String, Set<String>> keysByNode = new LinkedHashMap<>();
    BiFunction<AgentGraphNode, Map<String, Object>, Object> fn = (node, ctx) -> {
      keysByNode.put(node.getKey(), new HashSet<>(ctx.keySet()));
      return node.getKey() + "_result";
    };
    if (reverse) {
      graph.reverseTraverse(fn, initialCtx);
    } else {
      graph.traverse(fn, initialCtx);
    }
    return keysByNode;
  }

  // ---- traverse -------------------------------------------------------------

  @Test
  public void traverseVisitsAllNodesFromRoot() {
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"b", "c"}}, "a", "b", "c");
    List<String> visited = visitOrder(graph, false, new HashMap<String, Object>());
    assertThat(visited, contains("a", "b", "c"));
  }

  @Test
  public void traverseDoesNotMutateCallerContextWithNodeResults() {
    AgentGraphDefinition graph = buildEnabled("a", new String[][]{{"a", "b"}}, "a", "b");

    Map<String, Object> ctx = new HashMap<>();
    ctx.put("seed", "value");
    Map<String, Set<String>> keysByNode = captureContextKeys(graph, false, ctx);

    // Caller map is only the initial-context template; node results are not written back.
    assertThat(ctx.keySet(), containsInAnyOrder("seed"));
    assertThat(keysByNode.get("a"), containsInAnyOrder("seed"));
    assertThat(keysByNode.get("b"), containsInAnyOrder("seed", "a"));
    assertThat(keysByNode.get("b").contains("b"), is(false));
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
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"b", "a"}}, "a", "b");

    List<String> visited = visitOrder(graph, false, new HashMap<String, Object>());
    assertThat(visited.size(), is(2));
    assertThat(visited.get(0), is("a"));
    assertThat(new HashSet<>(visited), containsInAnyOrder("a", "b"));
  }

  // ---- reverseTraverse ------------------------------------------------------

  @Test
  public void reverseTraverseProcessesRootLast() {
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"b", "c"}}, "a", "b", "c");

    List<String> visited = visitOrder(graph, true, new HashMap<String, Object>());
    assertThat(visited, contains("c", "b", "a"));
  }

  @Test
  public void reverseTraverseVisitsAllNodes() {
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"a", "c"}}, "a", "b", "c");

    List<String> visited = visitOrder(graph, true, new HashMap<String, Object>());
    assertThat(visited, containsInAnyOrder("a", "b", "c"));
    assertThat(visited.get(visited.size() - 1), is("a"));
  }

  @Test
  public void reverseTraverseSingleNodeGraph() {
    AgentGraphDefinition graph = buildEnabled("a", null, "a");

    List<String> visited = visitOrder(graph, true, new HashMap<String, Object>());
    assertThat(visited, contains("a"));
  }

  @Test
  public void reverseTraverseHandlesCyclesSafely() {
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"b", "a"}}, "a", "b");

    List<String> visited = visitOrder(graph, true, new HashMap<String, Object>());
    // Cycle-safe: every reachable node visited exactly once; root last.
    assertThat(visited.size(), is(2));
    assertThat(visited.get(visited.size() - 1), is("a"));
    assertThat(new HashSet<>(visited), containsInAnyOrder("a", "b"));
  }

  @Test
  public void selfLoopIsNotIncludedInOwnContext() {
    // a → b → b (self-loop on b)
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"b", "b"}}, "a", "b");
    Map<String, Object> initial = new HashMap<>();
    initial.put("seed", 1);

    Map<String, Set<String>> fwd = captureContextKeys(graph, false, initial);
    assertThat(fwd.get("b"), containsInAnyOrder("seed", "a"));
    assertThat(fwd.get("b").contains("b"), is(false));

    Map<String, Set<String>> rev = captureContextKeys(graph, true, initial);
    assertThat(rev.get("b"), containsInAnyOrder("seed"));
    assertThat(rev.get("b").contains("b"), is(false));
    assertThat(rev.get("a"), containsInAnyOrder("seed", "b"));
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
    AgentGraphDefinition graph = buildEnabled("root",
        new String[][]{{"root", "a"}, {"root", "b"}, {"a", "sink"}, {"b", "sink"}},
        "root", "a", "b", "sink");

    List<String> visited = visitOrder(graph, false, new HashMap<String, Object>());
    assertThat(visited.get(0), is("root"));
    assertThat(visited, contains("root", "a", "b", "sink"));
  }

  @Test
  public void reverseTraverseDiamondGraph() {
    AgentGraphDefinition graph = buildEnabled("root",
        new String[][]{{"root", "a"}, {"root", "b"}, {"a", "sink"}, {"b", "sink"}},
        "root", "a", "b", "sink");

    List<String> visited = visitOrder(graph, true, new HashMap<String, Object>());
    assertThat(visited.get(visited.size() - 1), is("root"));
    assertThat(visited, contains("sink", "a", "b", "root"));
  }

  // ---- G1–G6 parity fixtures -------------------------------------------------

  @Test
  public void g1LinearExactOrder() {
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"b", "c"}}, "a", "b", "c");
    assertThat(visitOrder(graph, false, new HashMap<String, Object>()), contains("a", "b", "c"));
    assertThat(visitOrder(graph, true, new HashMap<String, Object>()), contains("c", "b", "a"));
  }

  @Test
  public void g2SkewedDiamondExactOrder() {
    // a→b, a→c, c→d, d→e, b→e
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"a", "c"}, {"c", "d"}, {"d", "e"}, {"b", "e"}},
        "a", "b", "c", "d", "e");
    assertThat(visitOrder(graph, false, new HashMap<String, Object>()),
        contains("a", "b", "c", "d", "e"));
    assertThat(visitOrder(graph, true, new HashMap<String, Object>()),
        contains("e", "b", "d", "c", "a"));
  }

  @Test
  public void g2EdgeOrderIndependence() {
    // Same as G2 but a's edges declared [c, b]
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "c"}, {"a", "b"}, {"c", "d"}, {"d", "e"}, {"b", "e"}},
        "a", "b", "c", "d", "e");
    List<String> forward = visitOrder(graph, false, new HashMap<String, Object>());
    assertThat(forward, contains("a", "c", "b", "d", "e"));
    assertThat(forward.indexOf("e") > forward.indexOf("d"), is(true));
  }

  @Test
  public void g3SymmetricDiamondExactOrder() {
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"a", "c"}, {"b", "d"}, {"c", "d"}},
        "a", "b", "c", "d");
    assertThat(visitOrder(graph, false, new HashMap<String, Object>()),
        contains("a", "b", "c", "d"));
    assertThat(visitOrder(graph, true, new HashMap<String, Object>()),
        contains("d", "b", "c", "a"));
  }

  @Test
  public void g4NestedParentExactOrder() {
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "n"}, {"n", "m"}, {"n", "t"}, {"m", "t"}},
        "a", "n", "m", "t");
    assertThat(visitOrder(graph, false, new HashMap<String, Object>()),
        contains("a", "n", "m", "t"));
    assertThat(visitOrder(graph, true, new HashMap<String, Object>()),
        contains("t", "m", "n", "a"));
  }

  @Test
  public void g5MultiTerminalExactOrder() {
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"a", "c"}, {"b", "d"}},
        "a", "b", "c", "d");
    assertThat(visitOrder(graph, false, new HashMap<String, Object>()),
        contains("a", "b", "c", "d"));
    assertThat(visitOrder(graph, true, new HashMap<String, Object>()),
        contains("c", "d", "b", "a"));
  }

  @Test
  public void g6CycleExactOrder() {
    // a→b, b→c, c→b
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"b", "c"}, {"c", "b"}},
        "a", "b", "c");
    List<String> forward = visitOrder(graph, false, new HashMap<String, Object>());
    List<String> reverse = visitOrder(graph, true, new HashMap<String, Object>());
    assertThat(forward, contains("a", "b", "c"));
    assertThat(reverse, contains("b", "c", "a"));
  }

  @Test
  public void g2ExactContextScopingForward() {
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"a", "c"}, {"c", "d"}, {"d", "e"}, {"b", "e"}},
        "a", "b", "c", "d", "e");
    Map<String, Object> initial = new HashMap<>();
    initial.put("seed", 1);
    Map<String, Set<String>> keys = captureContextKeys(graph, false, initial);

    assertThat(keys.get("a"), containsInAnyOrder("seed"));
    assertThat(keys.get("b"), containsInAnyOrder("seed", "a"));
    assertThat(keys.get("c"), containsInAnyOrder("seed", "a"));
    assertThat(keys.get("d"), containsInAnyOrder("seed", "a", "c"));
    assertThat(keys.get("e"), containsInAnyOrder("seed", "a", "b", "c", "d"));
    // Parallel-branch leak must not occur
    assertThat(keys.get("b").contains("c"), is(false));
    assertThat(keys.get("d").contains("c"), is(true)); // d's ancestor
    assertThat(keys.get("d").contains("b"), is(false));
  }

  @Test
  public void g2ExactContextScopingReverse() {
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"a", "c"}, {"c", "d"}, {"d", "e"}, {"b", "e"}},
        "a", "b", "c", "d", "e");
    Map<String, Object> initial = new HashMap<>();
    initial.put("seed", 1);
    Map<String, Set<String>> keys = captureContextKeys(graph, true, initial);

    assertThat(keys.get("e"), containsInAnyOrder("seed"));
    assertThat(keys.get("b"), containsInAnyOrder("seed", "e"));
    assertThat(keys.get("d"), containsInAnyOrder("seed", "e"));
    assertThat(keys.get("c"), containsInAnyOrder("seed", "d", "e"));
    assertThat(keys.get("a"), containsInAnyOrder("seed", "b", "c", "d", "e"));
    assertThat(keys.get("b").contains("c"), is(false));
    assertThat(keys.get("d").contains("c"), is(false));
  }

  @Test
  public void g2ContextScopingIndependentOfEdgeOrder() {
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "c"}, {"a", "b"}, {"c", "d"}, {"d", "e"}, {"b", "e"}},
        "a", "b", "c", "d", "e");
    Map<String, Set<String>> keys = captureContextKeys(graph, false, new HashMap<String, Object>());
    assertThat(keys.get("b").contains("c"), is(false));
    assertThat(keys.get("d").contains("b"), is(false));
    assertThat(keys.get("e"), containsInAnyOrder("a", "b", "c", "d"));
  }

  @Test
  public void traversalIsDeterministicAcrossRuns() {
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"a", "c"}, {"c", "d"}, {"d", "e"}, {"b", "e"}},
        "a", "b", "c", "d", "e");
    List<String> firstFwd = visitOrder(graph, false, new HashMap<String, Object>());
    List<String> firstRev = visitOrder(graph, true, new HashMap<String, Object>());
    for (int i = 0; i < 20; i++) {
      assertThat(visitOrder(graph, false, new HashMap<String, Object>()), is(firstFwd));
      assertThat(visitOrder(graph, true, new HashMap<String, Object>()), is(firstRev));
    }
  }

  @Test
  public void seededInitialContextVisibleToEveryNode() {
    AgentGraphDefinition graph = buildEnabled("a",
        new String[][]{{"a", "b"}, {"b", "c"}}, "a", "b", "c");
    Map<String, Object> initial = new HashMap<>();
    initial.put("provider", "handle");
    Map<String, Set<String>> fwd = captureContextKeys(graph, false, initial);
    Map<String, Set<String>> rev = captureContextKeys(graph, true, initial);
    for (String key : new String[]{"a", "b", "c"}) {
      assertThat(fwd.get(key).contains("provider"), is(true));
      assertThat(rev.get(key).contains("provider"), is(true));
    }
  }
}
