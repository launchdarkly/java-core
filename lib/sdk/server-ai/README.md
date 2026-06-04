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

Full usage documentation, including AI Config retrieval, tracking, and manual judge evaluation, will be
added as the SDK is built out (see epic AIC-2629).

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
