# Change log

All notable changes to the project will be documented in this file. This project adheres to [Semantic Versioning](http://semver.org).

## [2.2.0](https://github.com/launchdarkly/java-core/compare/launchdarkly-java-sdk-common-v2.1.1...launchdarkly-java-sdk-common-2.2.0) (2025-05-08)


### Features

* Support inline context for custom and migration events ([#55](https://github.com/launchdarkly/java-core/issues/55)) ([7a6a1db](https://github.com/launchdarkly/java-core/commit/7a6a1db5bf1c0643dc19e0998137e9b16f16f7d8))

## [2.1.1] - 2023-11-13
### Fixed:
- Fixes NPE when interacting with Context created by use of `copyFrom`.  (Thanks, [
pedroafonsodias](https://github.com/launchdarkly/java-sdk-common/pull/15))

## [2.1.0] - 2023-08-03
### Changed:
- Deprecated LDUser and related functionality. Use LDContext instead. To learn more, read https://docs.launchdarkly.com/home/contexts.

## [2.0.1] - 2023-05-17
### Changed:
- Updating Gradle to 7.6

### Fixed:
- Fixed NPE when creating a multi-context that included one invalid context.

## [2.0.0] - 2022-12-01
This major version release of `java-sdk-common` corresponds to the upcoming v6.0.0 release of the LaunchDarkly Java SDK (`java-server-sdk`) and the v4.0.0 release of the LaunchDarkly Android SDK (`android-client-sdk`), and cannot be used with earlier SDK versions.

### Added:
- The types `LDContext` and `ContextKind` define the new "context" model. "Contexts" are a replacement for the earlier concept of "users"; they can be populated with attributes in more or less the same way as before, but they also support new behaviors. More information about these features will be included in the release notes for the v6.0.0 Java SDK and v4.0.0 Android SDK releases.
- The type `AttributeRef` defines the attribute reference syntax, for referencing subproperties of JSON objects in flag evaluations or private attribute configuration. Applications normally will not need to reference this type.

### Changed:
- It was previously allowable to set a user key to an empty string. In the new context model, the key is not allowed to be empty. Trying to use an empty key will cause evaluations to fail and return the default value.
- There is no longer such a thing as a `secondary` meta-attribute that affects percentage rollouts. If you set an attribute with that name in an `LDContext`, it will simply be a custom attribute like any other.
- The `anonymous` attribute in `LDUser` is now a simple boolean, with no distinction between a false state and a null state.

### Removed:
- Removed all types, fields, and methods that were deprecated as of the most recent release.
- Removed the `secondary` meta-attribute in `LDUser` and `LDUser.Builder`.

## [1.3.0] - 2022-01-28
### Added:
- In `EvaluationReason`, added optional status information related to the new Big Segments feature.

## [1.2.2] - 2022-01-24
### Fixed:
- The `com.launchdarkly.sdk.json` serialization methods were dropping any object property whose value was `null` (due to the internal use of Gson, and Gson's default behavior of always omitting null properties). This has been changed to always respect whatever properties are written by the serializer for a given type, since in some cases (such as a map of feature flag keys to values) the presence of a key with a null value might have a subtly different meaning than the absence of the key.

## [1.2.1] - 2021-11-30
### Fixed:
- Updated Gson to 2.8.9 for a [security bugfix](https://github.com/google/gson/pull/1991). This dependency change will also be made in the Java SDK; the version of Gson that is referenced in `java-sdk-common` is used only at compile time.

## [1.2.0] - 2021-06-17
### Added:
- The SDK now supports the ability to control the proportion of traffic allocation to an experiment. This works in conjunction with a new platform feature now available to early access customers.

## [1.1.2] - 2021-06-14
### Changed:
- Increased the compile-time dependency on `jackson-databind` to 2.10.5.1, due to [CVE-2020-25649](https://nvd.nist.gov/vuln/detail/CVE-2020-25649).
- Stopped including Gson and Jackson in the published runtime dependency list in Gradle module metadata. These artifacts were already being excluded from `pom.xml`, but were still showing up as transitive dependencies in any tools that used the module metadata. For the rationale behind excluding these dependencies, see `build-shared.gradle`.

## [1.1.1] - 2021-04-22
### Fixed:
- Fixed an issue in the Jackson integration that could cause `.0` to be added unnecessarily to integer numeric values when serializing objects with Jackson.

## [1.1.0] - 2021-04-22
This release makes improvements to the helper methods for using Gson and Jackson for JSON conversion.

### Added:
- `LDGson.valueToJsonElement` and `LDGson.valueMapToJsonElementMap`: convenience methods for applications that use Gson types.
- `LDValue.arrayOf()`

### Changed:
- In `com.launchdarkly.sdk.json`, the implementations of `LDGson.typeAdapters` and `LDJackson.module` have been changed for better efficiency in deserialization. Instead of creating an intermediate string representation and re-parsing that, they now have a more direct way for the internal deserialization logic to interact with the streaming parser in the application&#39;s Gson or Jackson instance.

### Fixed:
- `Gson.toJsonTree` now works with LaunchDarkly types, as long as you have configured it as described in `com.launchdarkly.sdk.json.LDGson`. Previously, Gson was able to convert these types to and from JSON string data, but `toJsonTree` did not work due to a [known issue](https://github.com/google/gson/issues/1289) with the `JsonWriter.jsonValue` method; the SDK code no longer uses that method.
- `LDValue.parse()` now returns `LDValue.ofNull()` instead of an actual null reference if the JSON string is `null`.
- Similarly, when deserializing an `EvaluationDetail<LDValue>` from JSON, if the `value` property is `null`, it will now translate this into `LDValue.ofNull()` rather than an actual null reference.

## [1.0.0] - 2020-06-01
Initial release, corresponding to version 5.0.0 of the server-side Java SDK.
