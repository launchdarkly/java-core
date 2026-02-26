# Change log

All notable changes to the project will be documented in this file. This project adheres to [Semantic Versioning](http://semver.org).

## [1.9.0](https://github.com/launchdarkly/java-core/compare/launchdarkly-java-sdk-internal-1.8.0...launchdarkly-java-sdk-internal-1.9.0) (2026-02-26)


### Features

* commonizes several FDv2 related types ([a14beb9](https://github.com/launchdarkly/java-core/commit/a14beb987e3c9f049c6f81c9771bddce7ba7591d))

## [1.8.0](https://github.com/launchdarkly/java-core/compare/launchdarkly-java-sdk-internal-1.7.0...launchdarkly-java-sdk-internal-1.8.0) (2026-02-12)


### Features

* Add optional support for per-context summary events. ([#135](https://github.com/launchdarkly/java-core/issues/135)) ([3913d6f](https://github.com/launchdarkly/java-core/commit/3913d6f905b53c5f81bc7d5d044f332343b07182))

## [1.7.0](https://github.com/launchdarkly/java-core/compare/launchdarkly-java-sdk-internal-1.6.1...launchdarkly-java-sdk-internal-1.7.0) (2026-02-03)


### Features

* Move iterable async queue to internal. ([#125](https://github.com/launchdarkly/java-core/issues/125)) ([971f4b3](https://github.com/launchdarkly/java-core/commit/971f4b357575405afe23cf3441f8835dea45a30e))

## [1.6.1](https://github.com/launchdarkly/java-core/compare/launchdarkly-java-sdk-internal-1.6.0...launchdarkly-java-sdk-internal-1.6.1) (2026-01-13)


### Bug Fixes

* making Selector make public ([#102](https://github.com/launchdarkly/java-core/issues/102)) ([9f4f2ee](https://github.com/launchdarkly/java-core/commit/9f4f2ee89e7a8e8f168ceb7e8a7a4c8ced4f3901))

## [1.6.0](https://github.com/launchdarkly/java-core/compare/launchdarkly-java-sdk-internal-1.5.1...launchdarkly-java-sdk-internal-1.6.0) (2026-01-13)


### Features

* adds fdv2 payload parsing and protocol handling ([a1412c4](https://github.com/launchdarkly/java-core/commit/a1412c4f217c5c39a8667381ea51e1c758d7c548))

## [1.5.1](https://github.com/launchdarkly/java-core/compare/launchdarkly-java-sdk-internal-1.5.0...launchdarkly-java-sdk-internal-1.5.1) (2025-08-26)


### Bug Fixes

* updating various dependencies to latest minor to incorporate fixes ([b4425d7](https://github.com/launchdarkly/java-core/commit/b4425d74cc5db3c2cba1768b95b1fb903e591684))

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
