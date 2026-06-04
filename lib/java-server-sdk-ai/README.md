# LaunchDarkly Server-side AI SDK for Java

This package provides AI Config functionality for the LaunchDarkly Java Server SDK. It lets you manage prompts, models, and providers as LaunchDarkly AI Configs, interpolate variables into messages and instructions, and record AI metrics (duration, token usage, generation success, feedback, tool calls, and online judge evaluations) back to LaunchDarkly.

It is built on top of the [LaunchDarkly Java Server SDK](https://github.com/launchdarkly/java-server-sdk) and is feature-compatible with the [LaunchDarkly Server-side AI SDK for Python](https://github.com/launchdarkly/python-server-sdk-ai).

## LaunchDarkly overview

[LaunchDarkly](https://www.launchdarkly.com) is a feature management platform that serves over 100 billion feature flags daily to help teams build better software, faster. [Get started](https://docs.launchdarkly.com/home/getting-started) using LaunchDarkly today!

[![Twitter Follow](https://img.shields.io/twitter/follow/launchdarkly.svg?style=social&label=Follow&maxAge=2592000)](https://twitter.com/intent/follow?screen_name=launchdarkly)

## Supported Java versions

This package is compatible with Java 8 and above, matching the LaunchDarkly Java Server SDK.

## Getting started

1. Add the `launchdarkly-java-server-sdk-ai` package to your project, alongside the core Java Server SDK:

```
dependencies {
    implementation "com.launchdarkly:launchdarkly-java-server-sdk:7+"
    implementation "com.launchdarkly:launchdarkly-java-server-sdk-ai:0.1.0"
}
```

2. Construct an `LDAIClient`, wrapping your existing `LDClient`:

```java
import com.launchdarkly.sdk.*;
import com.launchdarkly.sdk.server.*;
import com.launchdarkly.sdk.server.ai.*;
import com.launchdarkly.sdk.server.ai.datamodel.*;

LDClient ldClient = new LDClient("sdk-key-123abc");
LDAIClient aiClient = new LDAIClient(ldClient);
```

A single `LDAIClient` should be reused for the lifetime of your application.

## Completion configs

A completion config carries chat-style messages, a model, and a provider. Provide a default to use when the AI Config is unavailable, and any variables to interpolate into the messages.

```java
import java.util.Collections;
import java.util.Arrays;

LDContext context = LDContext.create("user-key-123abc");

AICompletionConfig config = aiClient.completionConfig(
    "my-ai-config",
    context,
    AICompletionConfigDefault.builder()
        .enabled(true)
        .model(new ModelConfig("gpt-4"))
        .messages(Arrays.asList(
            LDMessage.system("You are a helpful assistant named {{name}}.")))
        .build(),
    Collections.singletonMap("name", "Bailey"));

if (config.isEnabled()) {
    AIConfigTracker tracker = config.createTracker();
    String answer = tracker.trackDurationOf(() -> callYourModel(config));
    tracker.trackSuccess();
}
```

Message and instruction content is interpolated with Mustache templating. In addition to any
variables you supply, an `ldctx` variable is always available, exposing the attributes of the
evaluation context (for example `{{ldctx.name}}`, or `{{ldctx.user.name}}` for a multi-context).

## Agent configs

An agent config carries freeform `instructions` rather than chat messages. Retrieve a single agent
with `agentConfig`, or several at once with `agentConfigs`:

```java
AIAgentConfig agent = aiClient.agentConfig(
    "research_agent",
    context,
    AIAgentConfigDefault.builder()
        .enabled(true)
        .model(new ModelConfig("gpt-4"))
        .instructions("You are a research assistant specializing in {{topic}}.")
        .build(),
    Collections.singletonMap("topic", "climate change"));
```

## Tracking metrics

The `AIConfigTracker` returned by `createTracker()` records metrics for a single AI run. Each scalar
metric (duration, success/error, feedback, tokens, time-to-first-token) is recorded at most once per
tracker; obtain a new tracker for each run.

```java
AIConfigTracker tracker = config.createTracker();

tracker.trackDuration(durationMs);
tracker.trackTokens(new TokenUsage(/* total */ 30, /* input */ 10, /* output */ 20));
tracker.trackSuccess();          // or tracker.trackError();
tracker.trackFeedback(FeedbackKind.POSITIVE);
```

To associate deferred events (such as user feedback gathered later) with the original run, persist
the tracker's resumption token and reconstruct the tracker from it:

```java
String token = tracker.getResumptionToken();
// ...later, possibly in a different process...
AIConfigTracker resumed = aiClient.createTracker(token, context);
resumed.trackFeedback(FeedbackKind.POSITIVE);
```

## Contributing

We encourage pull requests and other contributions from the community. Check out our [contributing guidelines](../../CONTRIBUTING.md) for instructions on how to contribute to this SDK.

## About LaunchDarkly

- LaunchDarkly is a continuous delivery platform that provides feature flags as a service and allows developers to iterate quickly and safely. We allow you to easily flag your features and manage them from the LaunchDarkly dashboard. With LaunchDarkly, you can:
  - Roll out a new feature to a subset of your users (like a group of users who opt-in to a beta tester group), gathering feedback and bug reports from real-world use cases.
  - Gradually roll out a feature to an increasing percentage of users, and track the effect that the feature has on key metrics (for instance, how likely is a user to complete a purchase if they have feature A versus feature B?).
  - Turn off a feature that you realize is causing performance problems in production, without needing to re-deploy, or even restart the application with a changed configuration file.
  - Grant access to certain features based on user attributes, like payment plan (eg: users on the 'gold' plan get access to more features than users in the 'silver' plan).
  - Disable parts of your application to facilitate maintenance, without taking everything offline.
- LaunchDarkly provides feature flag SDKs for a wide variety of languages and technologies. Check out [our documentation](https://docs.launchdarkly.com/sdk) for a complete list.
- Explore LaunchDarkly
  - [launchdarkly.com](https://www.launchdarkly.com/ 'LaunchDarkly Main Website') for more information
  - [docs.launchdarkly.com](https://docs.launchdarkly.com/ 'LaunchDarkly Documentation') for our documentation and SDK reference guides
  - [apidocs.launchdarkly.com](https://apidocs.launchdarkly.com/ 'LaunchDarkly API Documentation') for our API documentation
  - [blog.launchdarkly.com](https://blog.launchdarkly.com/ 'LaunchDarkly Blog Documentation') for the latest product updates
