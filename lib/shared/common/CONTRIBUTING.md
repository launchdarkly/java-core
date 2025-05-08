# Contributing to the LaunchDarkly SDK Java Common Code
 
LaunchDarkly has published an [SDK contributor's guide](https://docs.launchdarkly.com/sdk/concepts/contributors-guide) that provides a detailed explanation of how our SDKs work. See below for additional information on how to contribute to this project.
 
## Submitting bug reports and feature requests
 
The LaunchDarkly SDK team monitors the [issue tracker](https://github.com/launchdarkly/java-sdk-common/issues) in the GitHub repository. Bug reports and feature requests specific to this project should be filed in this issue tracker. The SDK team will respond to all newly filed issues within two business days.
 
## Submitting pull requests
 
We encourage pull requests and other contributions from the community. Before submitting pull requests, ensure that all temporary or unintended code is removed. Don't worry about adding reviewers to the pull request; the LaunchDarkly SDK team will add themselves. The SDK team will acknowledge all pull requests within two business days.
 
## Release notes and `@since`

Since this project is meant to be used from multiple LaunchDarkly SDKs and its Javadoc documentation will also appear in the Javadocs for those SDKs, please use the following conventions:

1. All changes and fixes should be documented in the changelog and release notes for this project as part of the usual release process.
2. They should _also_ be documented in the changelogs and release notes for the next Java/Android SDK releases that incorporate the new `java-sdk-common` release. Users of those should not be expected to monitor this repository; its existence as a separate project is an implementation detail.
3. When adding a new public type or method, include a `@since` tag in its Javadoc comment, in the following format: `@since Java server-side SDK $NEXT_JAVA_VERSION / Android SDK $NEXT_ANDROID_VERSION`, where `$NEXT_JAVA_VERSION` and `$NEXT_ANDROID_VERSION` are the next minor version releases of those SDKs that will incorporate this feature-- even though those have not been released yet.


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

If you wish to use your generated SDK artifact by another Maven/Gradle project such as [java-server-sdk](https://github.com/launchdarkly/java-server-sdk), you will likely want to publish the artifact to your local Maven repository so that your other project can access it. You can append `-SNAPSHOT` to the version in [gradle.properties](gradle.properties) and update any dependancies to test the snapshot version.
```
./gradlew publishToMavenLocal -PskipSigning=true
```

### Testing
 
To build the project and run all unit tests:
```
./gradlew test
```

## Note on Java version and Android support

This project is limited to Java 7 because it is used in both the LaunchDarkly server-side Java SDK and the LaunchDarkly Android SDK. Android only supports Java 8 to a limited degree, depending on both the version of the Android developer tools and the Android API version. Since this is a small code base, we have decided to use Java 7 for it despite the minor inconveniences that this causes in terms of syntax.

## Code coverage

It is important to keep unit test coverage as close to 100% as possible in this project, since the SDK projects will not exercise every `java-sdk-common` method in their own unit tests.

You can view the latest code coverage report in CircleCI, as `coverage/html/index.html` in the artifacts for the "Java 11 - Linux - OpenJDK" job. You can also run the report locally with `./gradlew jacocoTestCoverage` and view `./build/reports/jacoco/test`.

Sometimes a gap in coverage is unavoidable, usually because the compiler requires us to provide a code path for some condition that in practice can't happen and can't be tested, or because of a known issue with the code coverage tool. Please handle all such cases as follows:

* Mark the code with an explanatory comment beginning with "COVERAGE:".
* Run the code coverage task with `./gradlew jacocoTestCoverageVerification`. It should fail and indicate how many lines of missed coverage exist in the method you modified.
* Add an item in the `knownMissedLinesForMethods` map in `build.gradle` that specifies that number of missed lines for that method signature.

## Note on dependencies

This project's `build.gradle` contains special logic to exclude dependencies from `pom.xml`. This is because it is meant to be used as part of one of the LaunchDarkly SDKs, and the different SDKs have different strategies for either exposing or embedding these dependencies. Therefore, it is the responsibility of each SDK to provide its own dependency for any module that is actually required in order for `java-sdk-common` to work; currently that is only Gson.
