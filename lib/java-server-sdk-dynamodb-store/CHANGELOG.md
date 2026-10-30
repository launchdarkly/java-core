# Change log

All notable changes to the LaunchDarkly Java SDK DynamoDB integration will be documented in this file. This project adheres to [Semantic Versioning](http://semver.org).

## [5.0.0] - 2022-12-07
This release corresponds to the 6.0.0 release of the LaunchDarkly Java SDK. Any application code that is being updated to use the 6.0.0 SDK, and was using a 4.x version of `launchdarkly-java-server-sdk-dynamodb`, should now use a 5.x version instead.

There are no functional differences in the behavior of the DynamoDB integration; the differences are only related to changes in the usage of interface types for configuration in the SDK.

### Added:
- `DynamoDb.bigSegmentStore()`, which creates a configuration builder for use with Big Segments. Previously, the `DynamoDb.dataStore()` builder was used for both regular data stores and Big Segment stores.

### Changed:
- The type `DynamoDbDataStoreBuilder` has been removed, replaced by a generic type `DynamoDbStoreBuilder`. Application code would not normally need to reference these types by name, but if necessary, use either `DynamoDbStoreBuilder<PersistentDataStore>` or `DynamoDbStoreBuilder<BigSegmentStore>` depending on whether you are configuring a regular data store or a Big Segment store.

## [4.0.0] - 2022-10-06
This release updates the package to use the new logging mechanism that was introduced in version [5.10.0](https://github.com/launchdarkly/java-server-sdk/releases/tag/5.10.0) of the LaunchDarkly Java SDK, so that log output from the DynamoDB integration is handled in whatever way was specified by the SDK's logging configuration, instead of always using SLF4J.

This version of the package will not work with SDK versions earlier than 5.10.0; that is the only reason for the 4.0.0 major version increment. The functionality of the package is otherwise unchanged, and there are no API changes.

## [3.1.1] - 2022-04-15
### Fixed:
- If the SDK attempts to store a feature flag or segment whose total data size is over the 400KB limit for DynamoDB items, this integration will now log (at `Error` level) a message like `The item "my-flag-key" in "features" was too large to store in DynamoDB and was dropped` but will still process all other data updates. Previously, it would cause the SDK to enter an error state in which the oversized item would be pointlessly retried and other updates might be lost.

## [3.1.0] - 2022-02-04
### Added:
- Added support for Big Segments. An Early Access Program for creating and syncing Big Segments from customer data platforms is available to enterprise customers.

## [3.0.0] - 2020-06-02
This release is for use with versions 5.0.0 and higher of [`launchdarkly-java-server-sdk`](https://github.com/launchdarkly/java-server-sdk).

For more information about changes in the SDK database integrations, see the [4.x to 5.0 migration guide](https://docs-stg.launchdarkly.com/252/sdk/server-side/java/migration-4-to-5/).

### Changed:
- The entry point is now `com.launchdarkly.sdk.server.integrations.DynamoDb` rather than `com.launchdarkly.client.integrations.DynamoDb`.
- The SLF4J logger name is now `com.launchdarkly.sdk.server.LDClient.DataStore.DynamoDb` rather than `com.launchdarkly.client.integrations.DynamoDbDataStoreImpl`.

### Removed:
- Removed the deprecated entry point `com.launchdarkly.client.dynamodb.DynamoDbComponents`.


## [2.1.0] - 2020-01-30
### Added:
- New classes `com.launchdarkly.client.integrations.DynamoDb` and `com.launchdarkly.client.integrations.DynamoDbDataStoreBuilder`, which serve the same purpose as the previous classes but are designed to work with the newer persistent data store API introduced in [Java SDK 4.12.0](https://github.com/launchdarkly/java-server-sdk/releases/tag/4.12.0).

### Deprecated:
- The old interface in the `com.launchdarkly.client.integrations.dynamodb` package.

## [2.0.2] - 2019-12-11
### Changed:
- Updated the AWS SDK DynamoDB dependency to version 2.10.32. Your application can always specify its own desired version of the dependency; this is just the version that is used for building the library.

## [2.0.1] - 2019-05-13
### Changed:
- Corresponding to the SDK package name change from `com.launchdarkly:launchdarkly-client` to `com.launchdarkly:launchdarkly-java-server-sdk`, this package is now called `com.launchdarkly:launchdarkly-java-server-sdk-dynamodb-store`. The functionality of the package, including the package names and class names in the code, has not changed.

## [1.0.1] - 2019-05-13
### Changed:
- Corresponding to the SDK package name change from `com.launchdarkly:launchdarkly-client` to `com.launchdarkly:launchdarkly-java-server-sdk`, this package is now called `com.launchdarkly:launchdarkly-java-server-sdk-dynamodb-store`. The functionality of the package, including the package names and class names in the code, has not changed.

## [2.0.0] - 2018-12-12

Initial release of the implementation that is based on AWS SDK 2.x. This has the same functionality as version 1.0.0, but uses the DynamoDB client classes from the more recent AWS API.

Note that AWS SDK 2.x has a minimum Java version of 8, so if you require Java 7 compatibility you should use the 1.x releases of this library.

## [1.0.0] - 2018-12-12

Initial release of the implementation that is based on AWS SDK 1.x.
