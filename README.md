# LaunchDarkly monorepo for Java SDKs and Java libs.

This repository contains LaunchDarkly SDK packages for usage in Java.
This includes shared libraries, used by SDKs and other tools, as well as SDKs.

## Packages

| SDK Packages                                                    | API Docs                                                            | maven                                                         | issues                                | tests                                                             |
| ------------------------------------------------------------------ |---------------------------------------------------------------------| ------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------------- |
| [@launchdarkly/java-server-sdk](lib/sdk/server/README.md)   | [![Documentation][server-sdk-docs-badge]][server-sdk-docs-link] | [![maven][server-sdk-maven-badge]][server-sdk-maven-link] | [Issues][server-sdk-issues]         | [![Actions Status][server-sdk-ci-badge]][server-sdk-ci-link]  |

| Shared Packages                                                    | API Docs                                                            | maven                                                         | issues                                | tests                                                             |
| ------------------------------------------------------------------ |---------------------------------------------------------------------| ------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------------- |
| [@launchdarkly/java-sdk-internal](lib/shared/internal/README.md)   | [![Documentation][sdk-internal-docs-badge]][sdk-internal-docs-link] | [![maven][sdk-internal-maven-badge]][sdk-internal-maven-link] | [Issues][sdk-internal-issues]         | [![Actions Status][sdk-internal-ci-badge]][sdk-internal-ci-link]  |
| [@launchdarkly/java-sdk-common](lib/shared/common/README.md)       | [![Documentation][sdk-common-docs-badge]][sdk-common-docs-link]     | [![maven][sdk-common-maven-badge]][sdk-common-maven-link]     | [Issues][sdk-common-issues]           | [![Actions Status][sdk-common-ci-badge]][sdk-common-ci-link]      |

| Other Packages                                                           | API Docs                                                     | maven                                                      | issues                                | tests                                                         |
| ---------------------------------------------------------------------------- |--------------------------------------------------------------| ---------------------------------------------------------- | ------------------------------------- | ------------------------------------------------------------- |
| [@launchdarkly/java-server-sdk-otel](lib/java-server-sdk-otel/README.md)     | [![Documentation][server-otel-docs-badge]][server-otel-docs-link]    | [![maven][server-otel-maven-badge]][server-otel-maven-link]         | [Issues][server-otel-issues]         | [![Actions Status][server-otel-ci-badge]][server-otel-ci-link]         |
| [@launchdarkly/java-server-sdk-redis-store](lib/java-server-sdk-redis-store/README.md)     | [![Documentation][server-redis-docs-badge]][server-redis-docs-link]    | [![maven][server-redis-maven-badge]][server-redis-maven-link]         | [Issues][server-redis-issues]         | [![Actions Status][server-redis-ci-badge]][server-redis-ci-link]         |

## Organization

`lib` Top level directory containing package implementations.

`lib/shared` Packages which are primarily intended for consumption by LaunchDarkly and are used in other packages types.

## LaunchDarkly overview

[LaunchDarkly](https://www.launchdarkly.com) is a feature management platform that serves trillions of feature flags daily to help teams build better software, faster. [Get started](https://docs.launchdarkly.com/home/getting-started) using LaunchDarkly today!

[![Twitter Follow](https://img.shields.io/twitter/follow/launchdarkly.svg?style=social&label=Follow&maxAge=2592000)](https://twitter.com/intent/follow?screen_name=launchdarkly)

## Testing

We run integration tests for all our SDKs using a centralized test harness. This approach gives us the ability to test for consistency across SDKs, as well as test networking behavior in a long-running application. These tests cover each method in the SDK, and verify that event sending, flag evaluation, stream reconnection, and other aspects of the SDK all behave correctly.

## Contributing

We encourage pull requests and other contributions from the community. Check out our [contributing guidelines](CONTRIBUTING.md) for instructions on how to contribute to this SDK.

## About LaunchDarkly

- LaunchDarkly is a continuous delivery platform that provides feature flags as a service and allows developers to iterate quickly and safely. We allow you to easily flag your features and manage them from the LaunchDarkly dashboard. With LaunchDarkly, you can:
  - Roll out a new feature to a subset of your users (like a group of users who opt-in to a beta tester group), gathering feedback and bug reports from real-world use cases.
  - Gradually roll out a feature to an increasing percentage of users, and track the effect that the feature has on key metrics (for instance, how likely is a user to complete a purchase if they have feature A versus feature B?).
  - Turn off a feature that you realize is causing performance problems in production, without needing to re-deploy, or even restart the application with a changed configuration file.
  - Grant access to certain features based on user attributes, like payment plan (eg: users on the ‘gold’ plan get access to more features than users in the ‘silver’ plan). 
  - Disable parts of your application to facilitate maintenance, without taking everything offline.
- LaunchDarkly provides feature flag SDKs for a wide variety of languages and technologies. Read [our documentation](https://docs.launchdarkly.com/sdk) for a complete list.
- Explore LaunchDarkly
  - [launchdarkly.com](https://www.launchdarkly.com/ 'LaunchDarkly Main Website') for more information
  - [docs.launchdarkly.com](https://docs.launchdarkly.com/ 'LaunchDarkly Documentation') for our documentation and SDK reference guides
  - [apidocs.launchdarkly.com](https://apidocs.launchdarkly.com/ 'LaunchDarkly API Documentation') for our API documentation
  - [blog.launchdarkly.com](https://blog.launchdarkly.com/ 'LaunchDarkly Blog Documentation') for the latest product updates

[//]: # 'java-server-sdk'
[server-sdk-issues]: https://github.com/launchdarkly/java-core/issues?q=is%3Aissue+is%3Aopen+label%3A%22package%3A+java-server-sdk%22+
[server-sdk-maven-badge]: https://img.shields.io/maven-central/v/com.launchdarkly/launchdarkly-java-server-sdk
[server-sdk-maven-link]: https://central.sonatype.com/artifact/com.launchdarkly/launchdarkly-java-server-sdk
[server-sdk-ci-badge]: https://github.com/launchdarkly/java-core/actions/workflows/java-server-sdk.yml/badge.svg
[server-sdk-ci-link]: https://github.com/launchdarkly/java-core/actions/workflows/java-server-sdk.yml
[server-sdk-docs-badge]: https://img.shields.io/static/v1?label=GitHub+Pages&message=API+reference&color=00add8
[server-sdk-docs-link]: https://launchdarkly.github.io/java-core/lib/sdk/server/

[//]: # 'java-server-sdk-otel'
[server-otel-issues]: https://github.com/launchdarkly/java-core/issues?q=is%3Aissue+is%3Aopen+label%3A%22package%3A+java-server-sdk-otel%22+
[server-otel-maven-badge]: https://img.shields.io/maven-central/v/com.launchdarkly/launchdarkly-java-server-sdk-otel
[server-otel-maven-link]: https://central.sonatype.com/artifact/com.launchdarkly/launchdarkly-java-server-sdk-otel
[server-otel-ci-badge]: https://github.com/launchdarkly/java-core/actions/workflows/java-server-sdk-otel.yml/badge.svg
[server-otel-ci-link]: https://github.com/launchdarkly/java-core/actions/workflows/java-server-sdk-otel.yml
[server-otel-docs-badge]: https://img.shields.io/static/v1?label=GitHub+Pages&message=API+reference&color=00add8
[server-otel-docs-link]: https://launchdarkly.github.io/java-core/lib/java-server-sdk-otel/

[//]: # 'java-server-sdk-redis-store'
[server-redis-issues]: https://github.com/launchdarkly/java-core/issues?q=is%3Aissue+is%3Aopen+label%3A%22package%3A+java-server-sdk-redis-store%22+
[server-redis-maven-badge]: https://img.shields.io/maven-central/v/com.launchdarkly/launchdarkly-java-server-sdk-redis-store
[server-redis-maven-link]: https://central.sonatype.com/artifact/com.launchdarkly/launchdarkly-java-server-sdk-redis-store
[server-redis-ci-badge]: https://github.com/launchdarkly/java-core/actions/workflows/java-server-sdk-redis-store.yml/badge.svg
[server-redis-ci-link]: https://github.com/launchdarkly/java-core/actions/workflows/java-server-sdk-redis-store.yml
[server-redis-docs-badge]: https://img.shields.io/static/v1?label=GitHub+Pages&message=API+reference&color=00add8
[server-redis-docs-link]: https://launchdarkly.github.io/java-core/lib/java-server-sdk-redis-store/

[//]: # 'java-sdk-internal'
[sdk-internal-issues]: https://github.com/launchdarkly/java-core/issues?q=is%3Aissue+is%3Aopen+label%3A%22package%3A+java-sdk-internal%22+
[sdk-internal-maven-badge]: https://img.shields.io/maven-central/v/com.launchdarkly/launchdarkly-java-sdk-internal
[sdk-internal-maven-link]: https://central.sonatype.com/artifact/com.launchdarkly/launchdarkly-java-sdk-internal
[sdk-internal-ci-badge]: https://github.com/launchdarkly/java-core/actions/workflows/java-sdk-internal.yml/badge.svg
[sdk-internal-ci-link]: https://github.com/launchdarkly/java-core/actions/workflows/java-sdk-internal.yml
[sdk-internal-docs-badge]: https://img.shields.io/static/v1?label=GitHub+Pages&message=API+reference&color=00add8
[sdk-internal-docs-link]: https://launchdarkly.github.io/java-core/lib/shared/internal/

[//]: # 'java-sdk-common'
[sdk-common-issues]: https://github.com/launchdarkly/java-core/issues?q=is%3Aissue+is%3Aopen+label%3A%22package%3A+java-sdk-common%22+
[sdk-common-maven-badge]: https://img.shields.io/maven-central/v/com.launchdarkly/launchdarkly-java-sdk-common
[sdk-common-maven-link]: https://central.sonatype.com/artifact/com.launchdarkly/launchdarkly-java-sdk-common
[sdk-common-ci-badge]: https://github.com/launchdarkly/java-core/actions/workflows/java-sdk-common.yml/badge.svg
[sdk-common-ci-link]: https://github.com/launchdarkly/java-core/actions/workflows/java-sdk-common.yml
[sdk-common-docs-badge]: https://img.shields.io/static/v1?label=GitHub+Pages&message=API+reference&color=00add8
[sdk-common-docs-link]: https://launchdarkly.github.io/java-core/lib/shared/common/
