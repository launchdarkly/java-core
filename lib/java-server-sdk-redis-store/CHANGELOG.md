# Change log

All notable changes to the LaunchDarkly Java SDK Redis integration will be documented in this file. This project adheres to [Semantic Versioning](http://semver.org).

## [3.0.2](https://github.com/launchdarkly/java-core/compare/launchdarkly-java-server-sdk-redis-store-v3.0.1...launchdarkly-java-server-sdk-redis-store-3.0.2) (2025-03-14)


### Bug Fixes

* bumping underlying launchdarkly-java-server-sdk API version to r… ([#46](https://github.com/launchdarkly/java-core/issues/46)) ([3eb9930](https://github.com/launchdarkly/java-core/commit/3eb9930276aa100edfc773708d565f79e889465b))

## [3.0.1](https://github.com/launchdarkly/java-core/compare/v3.0.0...3.0.1) (2025-03-14)


### Bug Fixes

* bumping underlying launchdarkly-java-server-sdk API version to r… ([#46](https://github.com/launchdarkly/java-core/issues/46)) ([3eb9930](https://github.com/launchdarkly/java-core/commit/3eb9930276aa100edfc773708d565f79e889465b))

## [3.0.0] - 2022-12-07
This release corresponds to the 6.0.0 release of the LaunchDarkly Java SDK. Any application code that is being updated to use the 6.0.0 SDK, and was using a 2.x version of `launchdarkly-java-server-sdk-redis-store`, should now use a 3.x version instead.

There are no functional differences in the behavior of the Redis integration; the differences are only related to changes in the usage of interface types for configuration in the SDK.

### Added:
- `Redis.bigSegmentStore()`, which creates a configuration builder for use with Big Segments. Previously, the `Redis.dataStore()` builder was used for both regular data stores and Big Segment stores.

### Changed:
- The type `RedisDataStoreBuilder` has been removed, replaced by a generic type `RedisStoreBuilder`. Application code would not normally need to reference these types by name, but if necessary, use either `RedisStoreBuilder<PersistentDataStore>` or `RedisStoreBuilder<BigSegmentStore>` depending on whether you are configuring a regular data store or a Big Segment store.

## [2.0.0] - 2022-07-29
This release updates the package to use the new logging mechanism that was introduced in version 5.10.0 of the LaunchDarkly Java SDK, so that log output from the Redis integration is handled in whatever way was specified by the SDK's logging configuration.

This version of the package will not work with SDK versions earlier than 5.10.0; that is the only reason for the 2.0.0 major version increment. The functionality of the package is otherwise unchanged, and there are no API changes.

## [1.1.0] - 2022-01-28
### Added:
- Added support for Big Segments. An Early Access Program for creating and syncing Big Segments from customer data platforms is available to enterprise customers.

## [1.0.1] - 2021-08-06
### Fixed:
- This integration now works with Jedis 3.x as well as Jedis 2.9.x. The default dependency is still 2.9.x, but an application can override this with a dependency on a 3.x version. (Thanks, [robotmlg](https://github.com/launchdarkly/java-server-sdk-redis/pull/3)!)

## [1.0.0] - 2020-06-02
Initial release, corresponding to the 5.0.0 release of [`launchdarkly-java-server-sdk`](https://github.com/launchdarkly/java-server-sdk).

Prior to that release, the Redis integration was built into the main SDK library. For more information about changes in the SDK database integrations, see the [4.x to 5.0 migration guide](https://docs-stg.launchdarkly.com/252/sdk/server-side/java/migration-4-to-5/).
