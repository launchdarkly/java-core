package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.server.ai.internal.AgentGraphFlagValue;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The fully resolved definition of an agent graph, containing all nodes and their edges.
 * <p>
 * An {@code AgentGraphDefinition} is obtained from {@link LDAIClient#agentGraph}. When
 * {@link #isEnabled()} returns {@code false}, the graph definition was not fetchable or failed
 * validation; in that case all node collections are empty and traversal methods are no-ops. Only
 * {@link #getConfig()} and {@link #createTracker()} remain meaningful, so callers can still inspect
 * the raw flag value and fire graph-level usage events for a disabled graph.
 * <p>
 * Traversal ({@link #traverse}, {@link #reverseTraverse}) visits each reachable node once in
 * topological order (predecessors-first or descendants-first), deterministically and cycle-safe.
 * Each visitor sees only the initial context plus that node's dependency results.
 * <p>
 * This class is thread-safe. All returned collections are unmodifiable.
 */
public final class AgentGraphDefinition {
  private final AgentGraphFlagValue flagValue;
  private final Map<String, AgentGraphNode> nodes;
  private final boolean enabled;
  private final Supplier<AIGraphTracker> trackerFactory;

  AgentGraphDefinition(
      AgentGraphFlagValue flagValue,
      Map<String, AgentGraphNode> nodes,
      boolean enabled,
      Supplier<AIGraphTracker> trackerFactory) {
    this.flagValue = flagValue;
    this.nodes = nodes;
    this.enabled = enabled;
    this.trackerFactory = trackerFactory;
  }

  /**
   * Returns {@code true} if this graph definition is enabled and all nodes were successfully
   * fetched.
   *
   * @return whether the graph is enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Returns the root node of the graph (the entry point).
   *
   * @return the root node, or {@code null} if the graph is disabled or the root key is not in the
   *     node map
   */
  public AgentGraphNode rootNode() {
    return nodes.get(flagValue.getRoot());
  }

  /**
   * Returns the node with the given key, or {@code null} if not found.
   *
   * @param nodeKey the node key to look up
   * @return the node, or {@code null}
   */
  public AgentGraphNode getNode(String nodeKey) {
    return nodes.get(nodeKey);
  }

  /**
   * Returns the immediate child nodes of the node with the given key, following its outgoing
   * edges.
   *
   * @param nodeKey the source node key
   * @return an unmodifiable list of child nodes; empty if the node is terminal or not found
   */
  public List<AgentGraphNode> getChildNodes(String nodeKey) {
    AgentGraphNode node = nodes.get(nodeKey);
    if (node == null) {
      return Collections.emptyList();
    }
    List<AgentGraphNode> children = new ArrayList<>();
    for (GraphEdge edge : node.getEdges()) {
      AgentGraphNode child = nodes.get(edge.getKey());
      if (child != null) {
        children.add(child);
      }
    }
    return Collections.unmodifiableList(children);
  }

  /**
   * Returns all nodes that have an outgoing edge pointing to the given node key.
   *
   * @param nodeKey the target node key
   * @return an unmodifiable list of parent nodes; empty if none found
   */
  public List<AgentGraphNode> getParentNodes(String nodeKey) {
    List<AgentGraphNode> parents = new ArrayList<>();
    for (AgentGraphNode node : nodes.values()) {
      for (GraphEdge edge : node.getEdges()) {
        if (nodeKey.equals(edge.getKey())) {
          parents.add(node);
          break;
        }
      }
    }
    return Collections.unmodifiableList(parents);
  }

  /**
   * Returns all terminal nodes (nodes with no outgoing edges).
   *
   * @return an unmodifiable list of terminal nodes; empty if the graph is disabled
   */
  public List<AgentGraphNode> terminalNodes() {
    List<AgentGraphNode> terminals = new ArrayList<>();
    for (AgentGraphNode node : nodes.values()) {
      if (node.isTerminal()) {
        terminals.add(node);
      }
    }
    return Collections.unmodifiableList(terminals);
  }

  /**
   * Returns the internal parsed flag value for this graph. This is an internal type and is not
   * part of the supported public API.
   *
   * @return the parsed flag value
   */
  AgentGraphFlagValue getConfig() {
    return flagValue;
  }

  /**
   * Creates a new {@link AIGraphTracker} for this graph invocation.
   * <p>
   * Each call produces a fresh tracker with a new run ID. A tracker is returned even when the
   * graph is disabled, so callers can still fire graph-level usage events (e.g. invocation
   * failure) when the graph's configuration could not be resolved.
   *
   * @return a new tracker, or {@code null} if no tracker factory was provided
   */
  public AIGraphTracker createTracker() {
    if (trackerFactory == null) {
      return null;
    }
    return trackerFactory.get();
  }

  /**
   * Topological traversal from the root (predecessors-first; root first).
   * <p>
   * A node runs only after all reachable predecessors. Ties break by discovery order (BFS from
   * root, declared edge order). Cycle-safe: each reachable node is visited once.
   * <p>
   * {@code fn} receives a fresh map of the initial {@code ctx} plus that node's predecessor
   * results only. {@code ctx} itself is not mutated. No-op if disabled or root is absent.
   *
   * @param fn visitor; node and dependency-scoped context
   * @param ctx initial context template (global scratch); not written with node results
   */
  public void traverse(BiFunction<AgentGraphNode, Map<String, Object>, Object> fn,
      Map<String, Object> ctx) {
    AgentGraphNode root = rootNode();
    if (root == null) {
      return;
    }

    Map.Entry<Set<String>, List<String>> rd = reachableAndDiscovery(root.getKey());
    Set<String> reachable = rd.getKey();
    List<String> order = rd.getValue();

    Map<String, Integer> indeg = new HashMap<>();
    for (String k : reachable) {
      indeg.put(k, 0);
    }
    for (String k : reachable) {
      AgentGraphNode node = getNode(k);
      if (node == null) {
        continue;
      }
      for (GraphEdge e : node.getEdges()) {
        if (reachable.contains(e.getKey())) {
          indeg.merge(e.getKey(), 1, Integer::sum);
        }
      }
    }
    indeg.put(root.getKey(), 0);

    Set<String> visited = new HashSet<>();
    Map<String, Object> results = new HashMap<>();
    Map<String, Set<String>> ancestors = new HashMap<>();
    while (visited.size() < reachable.size()) {
      String next = firstReady(order, visited, indeg);
      if (next == null) {
        next = lowestDegree(order, visited, indeg);
      }

      // Accumulate deps before marking visited so a self-loop does not count as its own ancestor.
      Set<String> anc = new HashSet<>();
      for (AgentGraphNode parent : getParentNodes(next)) {
        String pk = parent.getKey();
        if (!visited.contains(pk) || !reachable.contains(pk)) {
          continue;
        }
        anc.add(pk);
        Set<String> parentAnc = ancestors.get(pk);
        if (parentAnc != null) {
          anc.addAll(parentAnc);
        }
      }
      ancestors.put(next, anc);
      visited.add(next);

      AgentGraphNode nextNode = getNode(next);
      results.put(next, fn.apply(nextNode, scopedCtx(ctx, results, anc)));

      if (nextNode != null) {
        for (GraphEdge e : nextNode.getEdges()) {
          if (reachable.contains(e.getKey())) {
            indeg.merge(e.getKey(), -1, Integer::sum);
          }
        }
      }
    }
  }

  /**
   * Reverse topological traversal (descendants-first; root last).
   * <p>
   * A node runs only after all reachable descendants. Ties break by discovery order. Cycle-safe,
   * including graphs with no terminals. Each reachable node is visited once.
   * <p>
   * {@code fn} receives a fresh map of the initial {@code ctx} plus that node's descendant
   * results only. {@code ctx} itself is not mutated. No-op if disabled or root is absent.
   *
   * @param fn visitor; node and dependency-scoped context
   * @param ctx initial context template (global scratch); not written with node results
   */
  public void reverseTraverse(BiFunction<AgentGraphNode, Map<String, Object>, Object> fn,
      Map<String, Object> ctx) {
    AgentGraphNode root = rootNode();
    if (root == null) {
      return;
    }
    String rootKey = root.getKey();

    Map.Entry<Set<String>, List<String>> rd = reachableAndDiscovery(rootKey);
    Set<String> reachable = rd.getKey();
    List<String> order = rd.getValue();

    Map<String, Integer> outdeg = new HashMap<>();
    for (String k : reachable) {
      int d = 0;
      AgentGraphNode node = getNode(k);
      if (node != null) {
        for (GraphEdge e : node.getEdges()) {
          // The root is visited last, outside this loop, so no node waits on it.
          if (!e.getKey().equals(rootKey) && reachable.contains(e.getKey())) {
            d++;
          }
        }
      }
      outdeg.put(k, d);
    }

    Set<String> visited = new HashSet<>();
    Map<String, Object> results = new HashMap<>();
    Map<String, Set<String>> descendants = new HashMap<>();
    while (hasNonRootRemaining(reachable, visited, rootKey)) {
      String next = firstReadyNonRoot(order, visited, outdeg, rootKey);
      if (next == null) {
        next = lowestDegreeNonRoot(order, visited, outdeg, rootKey);
      }

      // Accumulate deps before marking visited so a self-loop does not count as its own descendant.
      Set<String> desc = new HashSet<>();
      AgentGraphNode nextNode = getNode(next);
      if (nextNode != null) {
        for (GraphEdge e : nextNode.getEdges()) {
          String ck = e.getKey();
          if (!reachable.contains(ck) || !visited.contains(ck)) {
            continue;
          }
          desc.add(ck);
          Set<String> childDesc = descendants.get(ck);
          if (childDesc != null) {
            desc.addAll(childDesc);
          }
        }
      }
      descendants.put(next, desc);
      visited.add(next);
      results.put(next, fn.apply(nextNode, scopedCtx(ctx, results, desc)));

      for (AgentGraphNode parent : getParentNodes(next)) {
        String pk = parent.getKey();
        if (!pk.equals(rootKey) && reachable.contains(pk)) {
          outdeg.merge(pk, -1, Integer::sum);
        }
      }
    }

    Set<String> rootDeps = new HashSet<>();
    for (String k : reachable) {
      if (!k.equals(rootKey)) {
        rootDeps.add(k);
      }
    }
    results.put(rootKey, fn.apply(root, scopedCtx(ctx, results, rootDeps)));
  }

  /** Reachable set and discovery order (BFS from root, declared edge order). */
  private Map.Entry<Set<String>, List<String>> reachableAndDiscovery(String rootKey) {
    Set<String> reachable = new HashSet<>();
    List<String> order = new ArrayList<>();
    Queue<String> queue = new LinkedList<>();
    reachable.add(rootKey);
    order.add(rootKey);
    queue.add(rootKey);
    while (!queue.isEmpty()) {
      String key = queue.poll();
      AgentGraphNode node = getNode(key);
      if (node == null) {
        continue;
      }
      for (GraphEdge edge : node.getEdges()) {
        if (getNode(edge.getKey()) != null && reachable.add(edge.getKey())) {
          order.add(edge.getKey());
          queue.add(edge.getKey());
        }
      }
    }
    return new AbstractMap.SimpleEntry<>(reachable, order);
  }

  /** Copy of {@code initial} with {@code results} entries for {@code deps} overlaid. */
  private static Map<String, Object> scopedCtx(
      Map<String, Object> initial, Map<String, Object> results, Set<String> deps) {
    Map<String, Object> out = new HashMap<>(initial);
    for (String k : deps) {
      out.put(k, results.get(k));
    }
    return out;
  }

  private static String firstReady(
      List<String> order, Set<String> visited, Map<String, Integer> degree) {
    for (String k : order) {
      if (!visited.contains(k) && degree.get(k) != null && degree.get(k) == 0) {
        return k;
      }
    }
    return null;
  }

  private static String lowestDegree(
      List<String> order, Set<String> visited, Map<String, Integer> degree) {
    String best = null;
    int bestDeg = Integer.MAX_VALUE;
    for (String k : order) {
      if (visited.contains(k)) {
        continue;
      }
      Integer d = degree.get(k);
      int deg = d == null ? 0 : d;
      if (best == null || deg < bestDeg) {
        best = k;
        bestDeg = deg;
      }
    }
    return best;
  }

  private static String firstReadyNonRoot(
      List<String> order, Set<String> visited, Map<String, Integer> degree, String rootKey) {
    for (String k : order) {
      if (k.equals(rootKey) || visited.contains(k)) {
        continue;
      }
      if (degree.get(k) != null && degree.get(k) == 0) {
        return k;
      }
    }
    return null;
  }

  private static String lowestDegreeNonRoot(
      List<String> order, Set<String> visited, Map<String, Integer> degree, String rootKey) {
    String best = null;
    int bestDeg = Integer.MAX_VALUE;
    for (String k : order) {
      if (k.equals(rootKey) || visited.contains(k)) {
        continue;
      }
      Integer d = degree.get(k);
      int deg = d == null ? 0 : d;
      if (best == null || deg < bestDeg) {
        best = k;
        bestDeg = deg;
      }
    }
    return best;
  }

  private static boolean hasNonRootRemaining(
      Set<String> reachable, Set<String> visited, String rootKey) {
    for (String k : reachable) {
      if (!k.equals(rootKey) && !visited.contains(k)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Builds the node map from the parsed flag value and pre-fetched agent configs.
   * <p>
   * For each key in {@link #collectAllKeys}, looks up the agent config from {@code configs} and
   * the outgoing edges from the flag value's edge map. Returns an unmodifiable map.
   *
   * @param flagValue the parsed flag value
   * @param configs the pre-fetched agent configs keyed by node key
   * @return an unmodifiable map of nodes keyed by config key
   */
  static Map<String, AgentGraphNode> buildNodes(
      AgentGraphFlagValue flagValue, Map<String, AIAgentConfig> configs) {
    Set<String> allKeys = collectAllKeys(flagValue);
    Map<String, AgentGraphNode> result = new HashMap<>();
    for (String key : allKeys) {
      AIAgentConfig config = configs.get(key);
      if (config == null) {
        continue;
      }
      List<GraphEdge> edges = flagValue.getEdges().get(key);
      if (edges == null) {
        edges = Collections.emptyList();
      }
      result.put(key, new AgentGraphNode(key, config, edges));
    }
    return Collections.unmodifiableMap(result);
  }

  /**
   * Collects all unique node keys referenced anywhere in the flag value: the root key, all edge
   * source keys, and all edge target keys.
   *
   * @param flagValue the parsed flag value
   * @return the set of all unique node keys
   */
  static Set<String> collectAllKeys(AgentGraphFlagValue flagValue) {
    Set<String> keys = new HashSet<>();
    String root = flagValue.getRoot();
    if (root != null && !root.isEmpty()) {
      keys.add(root);
    }
    for (Map.Entry<String, List<GraphEdge>> entry : flagValue.getEdges().entrySet()) {
      keys.add(entry.getKey());
      for (GraphEdge edge : entry.getValue()) {
        keys.add(edge.getKey());
      }
    }
    return keys;
  }
}
