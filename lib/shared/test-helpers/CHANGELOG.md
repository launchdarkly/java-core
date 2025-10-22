# Change log

All notable changes to the project will be documented in this file. This project adheres to [Semantic Versioning](http://semver.org).

## [2.1.0](https://github.com/launchdarkly/java-core/compare/test-helpers-2.0.2...test-helpers-2.1.0) (2025-10-22)


### Features

* Migrate java-test-helpers to java-core. ([9c1e9f9](https://github.com/launchdarkly/java-core/commit/9c1e9f9a9a2ac498ecf125b0e8d81f2736785dc2))
* Vendor nanohttpd to remove package dependency. ([9c1e9f9](https://github.com/launchdarkly/java-core/commit/9c1e9f9a9a2ac498ecf125b0e8d81f2736785dc2))

## [2.0.2] - 2023-06-27
### Changed:
- Bumping Guava version to incorporate CVE fixes.

## [2.0.1] - 2022-12-18
(This release replaces the broken 2.0.0 release, which was accidentally duplicated from 1.3.0.)

This release improves compatibility of the library with Android code by removing usage of Java 8 APIs that are not supported in Android. It also revises the embedded HTTP mechanism to use a fork of `nanohttpd` rather than the heavier-weight Jetty.

### Changed:
- All methods that took a `java.time.Duration` now take `long, TimeUnit` instead.
- The `HttpServer` class is now based on a fork of the lightweight `nanohttpd` (https://github.com/launchdarkly-labs/nanohttpd) library. This should work correctly in any server-side Java environment; it has not been validated in Android, but the previous Jetty implementation did not work in Android anyway.

## [2.0.0] - 2022-11-17
This release improves compatibility of the library with Android code by removing usage of Java 8 APIs that are not supported in Android. It also revises the embedded HTTP mechanism to use a fork of `nanohttpd` rather than the heavier-weight Jetty.

### Changed:
- All methods that took a `java.time.Duration` now take `long, TimeUnit` instead.
- The `HttpServer` class is now based on a fork of the lightweight `nanohttpd` (https://github.com/launchdarkly-labs/nanohttpd) library. This should work correctly in any server-side Java environment; it has not been validated in Android, but the previous Jetty implementation did not work in Android anyway.

## [1.3.0] - 2022-08-29
### Added:
- `com.launchdarkly.testhelpers.tcptest`: this package is analogous to `httptest` but much simpler, providing a basic TCP listener that can be configured with behaviors like "close connections without sending a response" or "forward the connection to another port".
- `com.launchdarkly.testhelpers.httptest.SpecialHttpConfigurations`: test helpers to validate several standard kinds of HTTP client configurations.

## [1.2.0] - 2022-07-08
### Added:
- `TypeBehavior.singletonValueFactory` is a new method that can be used with `TypeBehavior.checkEqualsAndHashCode` to allow testing of types that have interned/singleton values.

## [1.1.1] - 2022-06-17
### Fixed:
- Fixed Hamcrest dependency to use `hamcrest-library` rather than `hamcrest-all`, because JUnit (which is commonly used in any unit test code that would also use Hamcrest) has a transitive dependency on `hamcrest-library` and using both would result in duplication on the classpath.

## [1.1.0] - 2021-07-21
### Added:
- `Assertions`, `ConcurrentHelpers`, `JsonAssertions`, `TempDir`, `TempFile`, `TypeBehavior`.

## [1.0.0] - 2021-06-25
Initial release.
