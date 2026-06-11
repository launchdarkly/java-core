# LaunchDarkly Server-Side AI SDK for Java

[![Circle CI](https://img.shields.io/badge/status-in%20development-orange)](https://github.com/launchdarkly/java-core)

> **Status:** In active development toward `v1.0.0`. APIs are not yet stable.

This library provides LaunchDarkly AI Config support for the LaunchDarkly Server-Side SDK for Java.
It is built on top of [`launchdarkly-java-server-sdk`](https://github.com/launchdarkly/java-core/tree/main/lib/sdk/server)
and lets you retrieve, interpolate, and track AI Configs (models, providers, messages, agents, and judges)
managed in the LaunchDarkly dashboard.

## Supported Java versions

This library has a minimum Java version of 8.

## Getting started

This module is part of the [`java-core`](https://github.com/launchdarkly/java-core) monorepo and is
published to Maven Central as `com.launchdarkly:launchdarkly-java-server-sdk-ai`.

Construct an `LDAIClient` from an initialized server-side `LDClient`, then retrieve a typed config:

```java
LDClient ldClient = new LDClient(sdkKey);
LDAIClient aiClient = new LDAIClientImpl(ldClient);

Map<String, Object> variables = new HashMap<>();
variables.put("username", "Sandy");

AICompletionConfig config = aiClient.completionConfig(
    "my-ai-config-key",
    context,
    AICompletionConfigDefault.disabled(), // fallback when the flag is absent
    variables);

if (config.isEnabled()) {
    // config.getModel(), config.getProvider(), and config.getMessages() (already interpolated)
    // are ready to pass to your model provider.
}
```

The companion `agentConfig`/`agentConfigs` and `judgeConfig` methods retrieve agent and judge
configs respectively. Within a prompt message or agent instruction, the evaluation context is
available as `{{ldctx}}` (for example `{{ldctx.key}}`).

## Tracking AI runs

Every retrieved config exposes a tracker via `config.createTracker()`. Use it to record duration,
time-to-first-token, success/error, token usage, tool calls, and feedback for an AI run. Trackers
are thread-safe, and at-most-once metrics (duration, time-to-first-token, outcome, feedback, tokens)
emit a single event even under concurrent calls. A run can be correlated across processes with
`tracker.getResumptionToken()` and rebuilt later via `aiClient.createTracker(token, context)`.

## Evaluating responses with judges (manual)

A judge is an AI Config with `mode: judge` that scores another config's output against an evaluation
metric.

In `v1.0`, evaluation is **manual only**. The SDK parses `judgeConfiguration` and exposes it on
configs, but it does **not** automatically invoke judges on completion or agent calls. Sample-rate
driven auto-attachment is deferred past `v1.0`. Because no provider-specific runners ship yet, you
supply your own `Runner` that calls your model and returns structured `{score, reasoning}` output.

```java
Runner runner = input -> {
    // Call your model with `input`, then return its score/reasoning as structured output.
    // metrics carries success/tokens/duration for the invocation.
    return RunnerResult.builder(Metrics.builder(true).build())
        .parsed(LDValue.buildObject().put("score", 0.9).put("reasoning", "grounded").build())
        .build();
};

Judge judge = aiClient.createJudge("my-judge-key", context, null, variables, runner, 1.0);
if (judge != null) {
    JudgeResult result = judge.evaluate(originalInput, modelOutput);
    // Recording the result is the caller's responsibility:
    completionTracker.trackJudgeResult(result);
}
```

`Evaluator` runs several judges over the same input/output with per-judge fault isolation and a
per-judge timeout, returning one `JudgeResult` per judge in order. `Evaluator.noop()` returns an
empty result list.

## Internal API convention

Public, supported types live directly under `com.launchdarkly.sdk.server.ai` (and its documented
subpackages). Anything under `com.launchdarkly.sdk.server.ai.internal` is implementation detail: it is
**not** part of the supported API, is excluded from the published Javadoc and sources jars, and may
change without notice.

## Contributing

We encourage pull requests and other contributions from the community. Check out our
[contributing guidelines](../../CONTRIBUTING.md) for instructions on how to contribute.

## About LaunchDarkly

LaunchDarkly is a feature management platform that serves trillions of feature flags daily to help teams
build better software, faster. [Get started](https://launchdarkly.com) using LaunchDarkly today!
