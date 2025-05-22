# Change log

All notable changes to the project will be documented in this file. This project adheres to [Semantic Versioning](http://semver.org).

## [1.5.0](https://github.com/launchdarkly/java-core/compare/launchdarkly-java-sdk-internal-1.4.0...launchdarkly-java-sdk-internal-1.5.0) (2025-05-21)


### Features

* Enable gzip option for sending events ([#66](https://github.com/launchdarkly/java-core/issues/66)) ([553883d](https://github.com/launchdarkly/java-core/commit/553883df07e60cf65ad3025eff30a9c6ca637262))

## [1.4.0](https://github.com/launchdarkly/java-core/compare/launchdarkly-java-sdk-internal-v1.3.0...launchdarkly-java-sdk-internal-1.4.0) (2025-05-08)


### Features

* Support inline context for custom and migration events ([#55](https://github.com/launchdarkly/java-core/issues/55)) ([7a6a1db](https://github.com/launchdarkly/java-core/commit/7a6a1db5bf1c0643dc19e0998137e9b16f16f7d8))

## [1.3.0] - 2024-03-13
### Changed:
- Redact anonymous attributes within feature events
- Always inline contexts for feature events

## [1.2.1] - 2023-11-14
### Fixed:
- Fixes NPE when interacting with Context created by use of `copyFrom`.  (Thanks, [
pedroafonsodias](https://github.com/launchdarkly/java-sdk-common/pull/15))

## [1.2.0] - 2023-10-11
### Added:
- Added support for the migration operation event.
- Added support for event sampling for feature events and migration operation events.

## [1.1.1] - 2023-06-27
### Changed:
- Bumping Guava version to incorporate CVE fixes.

## [1.1.0] - 2023-03-21
### Added:
- Additional query param related functionality to HttpHelpers

## [1.0.0] - 2022-12-05
Initial release of this project, for use in the upcoming 6.0.0 release of the LaunchDarkly Java SDK and 4.0.0 release of the LaunchDarkly Android SDK.
