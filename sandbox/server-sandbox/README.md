# Server Sandbox

A Java application for testing features in the LaunchDarkly Java Server SDK.

## Building

This project uses Gradle for building. The Gradle wrapper is included, so you don't need to install Gradle separately.

```bash
./gradlew build
```

## Dependency Resolution

The project is configured to check for SDK dependencies in the following order:

1. **mavenLocal** - For local development, build and publish the SDK locally first:
   ```bash
   cd ../../lib/sdk/server
   ./gradlew publishToMavenLocal
   ```

2. **mavenCentral** - Falls back to published versions from Maven Central

The build.gradle dependency uses a version range (7.10.+) to pick up the latest compatible version.

## Running

Set your LaunchDarkly SDK key either as an environment variable or by editing the SDK_KEY in Hello.java:

```bash
export LAUNCHDARKLY_SDK_KEY=your-sdk-key-here
./gradlew run
```

Or optionally set a custom flag key:

```bash
export LAUNCHDARKLY_SDK_KEY=your-sdk-key-here
export LAUNCHDARKLY_FLAG_KEY=your-flag-key
./gradlew run
```

## What it does

The Hello application:
- Initializes the LaunchDarkly SDK client
- Evaluates a feature flag (default: "sample-feature")
- Displays the flag value and shows a banner if the flag is on
- Listens for real-time flag changes
- Demonstrates proper SDK lifecycle management

## Development

This sandbox is useful for:
- Testing new SDK features during development
- Reproducing customer issues
- Validating SDK behavior changes
- Quick experimentation with SDK APIs
