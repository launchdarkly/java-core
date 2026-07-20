package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.server.ai.internal.AgentGraphFlagValue;

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
 * Traversal methods ({@link #traverse} and {@link #reverseTraverse}) are BFS-based and
 * cycle-safe: each node is visited at most once.
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
   * Performs a BFS traversal of the graph starting from the root node.
   * <p>
   * For each node visited, {@code fn} is called with the node and the mutable context map. The
   * return value of {@code fn} is stored in the context map under the node's key, making it
   * available to subsequently visited nodes. Each node is visited exactly once (cycle-safe).
   * <p>
   * This is a no-op when the graph is disabled or the root node is absent.
   *
   * @param fn the visitor function; receives the current node and the context map
   * @param ctx the mutable context map; values from earlier nodes are available to later ones
   */
  public void traverse(BiFunction<AgentGraphNode, Map<String, Object>, Object> fn,
      Map<String, Object> ctx) {
    AgentGraphNode root = rootNode();
    if (root == null) {
      return;
    }

    Set<String> visited = new HashSet<>();
    Queue<AgentGraphNode> queue = new LinkedList<>();
    visited.add(root.getKey());
    queue.add(root);

    while (!queue.isEmpty()) {
      AgentGraphNode node = queue.poll();
      Object result = fn.apply(node, ctx);
      ctx.put(node.getKey(), result);

      for (AgentGraphNode child : getChildNodes(node.getKey())) {
        if (visited.add(child.getKey())) {
          queue.add(child);
        }
      }
    }
  }

  /**
   * Performs a reverse BFS traversal of the graph, starting from terminal nodes and working
   * upward toward the root.
   * <p>
   * The root node is always processed last. Each node is visited exactly once (cycle-safe). This
   * is a no-op when the graph is disabled or there are no terminal nodes.
   *
   * @param fn the visitor function; receives the current node and the context map
   * @param ctx the mutable context map; values from earlier nodes are available to later ones
   */
  public void reverseTraverse(BiFunction<AgentGraphNode, Map<String, Object>, Object> fn,
      Map<String, Object> ctx) {
    AgentGraphNode root = rootNode();
    if (root == null) {
      return;
    }

    Set<String> visited = new HashSet<>();
    Queue<AgentGraphNode> queue = new LinkedList<>();

    // Seed from terminals, excluding root (it will be processed last).
    for (AgentGraphNode terminal : terminalNodes()) {
      if (!terminal.getKey().equals(root.getKey()) && visited.add(terminal.getKey())) {
        queue.add(terminal);
      }
    }

    while (!queue.isEmpty()) {
      AgentGraphNode node = queue.poll();
      Object result = fn.apply(node, ctx);
      ctx.put(node.getKey(), result);

      for (AgentGraphNode parent : getParentNodes(node.getKey())) {
        if (!parent.getKey().equals(root.getKey()) && visited.add(parent.getKey())) {
          queue.add(parent);
        }
      }
    }

    // Process root last (whether or not it was encountered as a parent above).
    if (visited.add(root.getKey())) {
      Object result = fn.apply(root, ctx);
      ctx.put(root.getKey(), result);
    }
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
