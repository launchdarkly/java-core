# Change log

All notable changes to the project will be documented in this file. This project adheres to [Semantic Versioning](http://semver.org).

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
