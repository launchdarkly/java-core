# Contributing to the LaunchDarkly SDK Java Internal Common Code
 
LaunchDarkly has published an [SDK contributor's guide](https://docs.launchdarkly.com/sdk/concepts/contributors-guide) that provides a detailed explanation of how our SDKs work. See below for additional information on how to contribute to this project.
 
## Submitting bug reports and feature requests
 
The LaunchDarkly SDK team monitors the [issue tracker](https://github.com/launchdarkly/java-sdk-internal/issues) in the GitHub repository. Bug reports and feature requests specific to this project should be filed in this issue tracker. The SDK team will respond to all newly filed issues within two business days.
 
## Submitting pull requests
 
We encourage pull requests and other contributions from the community. Before submitting pull requests, ensure that all temporary or unintended code is removed. Don't worry about adding reviewers to the pull request; the LaunchDarkly SDK team will add themselves. The SDK team will acknowledge all pull requests within two business days.

## Access modifiers

The types in this library are meant to be consumed by our SDK code, and never seen by application developers. However, for any type that will be referenced directly from SDK code (as opposed to an implementation detail that is only referenced from within the `java-sdk-internal` code), the access modifier must be `public`. These types cannot be package-private, because we need to be able to access them from SDK code in multiple packages (e.g. `com.launchdarkly.sdk.server` versus `com.launchdarkly.sdk.android`).

That means it is technically possible for application code to see these types; the compiler will not stop a developer from referencing them. However:

1. We are explicitly declaring all APIs in this library to be unsupported for customer use, so any such use is at the developer's own risk.
2. Generated Javadoc documentation for the SDKs will not show these types, since they are in a dependency of the SDK rather than in the main SDK jar (and, in the case of the server-side Java SDK, these classes are obfuscated via shading).

## Versioning

The semantic versioning of this package refers to how the package is used from the point of view of internal SDK code. This is intentionally decoupled from the versioning of the SDKs themselves.

If a feature is added for the SDKs to use, such as a new helper class or a new overload of an existing method, then a minor version increment is appropriate. That does _not_ mean that the SDKs themselves would have a minor version increment, unless they are exposing some new functionality for application code to use.

If a change is made that is not backward-compatible, so SDK code will need to be modified to be able to use the new release, then a major version increment is appropriate. Again, that does _not_ mean that the SDKs themselves would have a major version increment, unless they have a breaking change from the point of view of application code.

## Build instructions
 
### Prerequisites
 
The project builds with [Gradle](https://gradle.org/) and should be built against Java 8.
 
### Building

To build the project without running any tests:
```
./gradlew jar
```

If you wish to clean your working directory between builds, you can clean it by running:
```
./gradlew clean
```

If you wish to use your generated SDK artifact by another Maven/Gradle project such as [java-server-sdk](https://github.com/launchdarkly/java-server-sdk), you will likely want to publish the artifact to your local Maven repository so that your other project can access it.
```
./gradlew publishToMavenLocal
```

### Testing
 
To build the project and run all unit tests:
```
./gradlew test
```

## Note on Java version, Android support, and dependencies

This project can be used both in server-side Java and in Android. Its minimum Java version is 8, but not all Java 8 APIs and syntax are supported in Android. The CI jobs for this project include an Android job that runs all of the unit tests in Android, to verify that no unsupported APIs are being used.

## Code coverage

It is important to keep unit test coverage as close to 100% as possible in this project, since the SDK projects will not exercise every `java-sdk-internal` method in their own unit tests.

You can view the latest code coverage report in CircleCI, as `coverage/html/index.html` in the artifacts for the "Java 11 - Linux - OpenJDK" job. You can also run the report locally with `./gradlew jacocoTestCoverage` and view `./build/reports/jacoco/test`.

Sometimes a gap in coverage is unavoidable, usually because the compiler requires us to provide a code path for some condition that in practice can't happen and can't be tested, or because of a known issue with the code coverage tool. Please handle all such cases as follows:

* Mark the code with an explanatory comment beginning with "COVERAGE:".
* Run the code coverage task with `./gradlew jacocoTestCoverageVerification`. It should fail and indicate how many lines of missed coverage exist in the method you modified.
* Add an item in the `knownMissedLinesForMethods` map in `build.gradle` that specifies that number of missed lines for that method signature.

## Note on dependencies

Because this project can be used in Android, it's important to avoid heavyweight runtime dependencies. For instance, as convenient as Guava can be, we should not use Guava at all (except possibly in _test_ code) because it is a large library-- and also because if the application does use Guava, we don't want to have to worry about conflicting with whatever version they're using.
