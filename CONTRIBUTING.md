# Contributing to the LaunchDarkly monorepo for Java SDKs
 
LaunchDarkly has published an [SDK contributor's guide](https://docs.launchdarkly.com/sdk/concepts/contributors-guide) that provides a detailed explanation of how our SDKs work. See below for additional information on how to contribute to this SDK.
 
## Submitting bug reports and feature requests
 
The LaunchDarkly SDK team monitors the [issue tracker](https://github.com/launchdarkly/java-core/issues) in the SDK repository. Bug reports and feature requests specific to this repository's packages should be filed in this issue tracker. The SDK team will make best effort to respond to all newly filed issues within two business days.
 
## Submitting pull requests
 
We encourage pull requests and other contributions from the community. Before submitting pull requests, ensure that all temporary or unintended code is removed. Don't worry about adding reviewers to the pull request; the LaunchDarkly SDK team will add themselves. The SDK team will acknowledge all pull requests within two business days.
 
## Build instructions
 
### Prerequisites
 
The majority of packages in this repo are built with [Gradle](https://gradle.org/) and should be built against Java 8.

### Building

To build a package without running any tests:
```
<package_path>/gradlew jar -p <package_path>
```

If you wish to clean your working directory between builds, you can clean it by running:
```
<package_path>/gradlew clean -p <package_path>
```

If you wish to use your generated package artifact by another Maven/Gradle project, you will likely want to publish the artifact to your local Maven repository so that your other project can access it.
```
<package_path>/gradlew publishToMavenLocal  -p <package_path> -PskipSigning=true
```

### Testing
 
To build the package and run all unit tests:
```
<package_path>/gradlew test -p <package_path>
```
