# LaunchDarkly SDK Java Common Code

[![Circle CI](https://circleci.com/gh/launchdarkly/java-sdk-common.svg?style=shield)](https://circleci.com/gh/launchdarkly/java-sdk-common)
[![Javadocs](http://javadoc.io/badge/com.launchdarkly/launchdarkly-java-sdk-common.svg)](http://javadoc.io/doc/com.launchdarkly/launchdarkly-java-sdk-common)

This project contains Java classes and interfaces that are shared between the LaunchDarkly server-side Java SDK and the LaunchDarkly Android SDK. Code that is specific to one or the other is in [java-server-sdk](https://github.com/launchdarkly/java-server-sdk) or [android-client-sdk](https://github.com/launchdarkly/android-client-sdk).

## Supported Java versions

This version of the library works with Java 7 and above.

## Contributing

See [Contributing](CONTRIBUTING.md).

## About LaunchDarkly
 
* LaunchDarkly is a continuous delivery platform that provides feature flags as a service and allows developers to iterate quickly and safely. We allow you to easily flag your features and manage them from the LaunchDarkly dashboard.  With LaunchDarkly, you can:
    * Roll out a new feature to a subset of your users (like a group of users who opt-in to a beta tester group), gathering feedback and bug reports from real-world use cases.
    * Gradually roll out a feature to an increasing percentage of users, and track the effect that the feature has on key metrics (for instance, how likely is a user to complete a purchase if they have feature A versus feature B?).
    * Turn off a feature that you realize is causing performance problems in production, without needing to re-deploy, or even restart the application with a changed configuration file.
    * Grant access to certain features based on user attributes, like payment plan (eg: users on the ‘gold’ plan get access to more features than users in the ‘silver’ plan). Disable parts of your application to facilitate maintenance, without taking everything offline.
* LaunchDarkly provides feature flag SDKs for a wide variety of languages and technologies. Read [our documentation](https://docs.launchdarkly.com/sdk) for a complete list.
* Explore LaunchDarkly
    * [launchdarkly.com](https://www.launchdarkly.com/ "LaunchDarkly Main Website") for more information
    * [docs.launchdarkly.com](https://docs.launchdarkly.com/  "LaunchDarkly Documentation") for our documentation and SDK reference guides
    * [apidocs.launchdarkly.com](https://apidocs.launchdarkly.com/  "LaunchDarkly API Documentation") for our API documentation
    * [blog.launchdarkly.com](https://blog.launchdarkly.com/  "LaunchDarkly Blog Documentation") for the latest product updates
