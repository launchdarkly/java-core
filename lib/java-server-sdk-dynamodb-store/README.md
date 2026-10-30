# LaunchDarkly SDK for Java - DynamoDB integration

This library provides a DynamoDB-backed persistence mechanism (data store) for the [LaunchDarkly Java SDK](https://github.com/launchdarkly/java-server-sdk), replacing the default in-memory data store.

This version of the library requires at least version 6.0.0 of the LaunchDarkly Java SDK, and at least version 2.1 of the AWS SDK for Java. For versions of the library to use with earlier SDK versions, see the changelog. The minimum Java version is 8.

See the [API documentation](https://launchdarkly.github.io/java-core/lib/java-server-sdk-dynamodb-store/) for details on classes and methods.

For more information, see also: [Using DynamoDB as a persistent feature store](https://docs.launchdarkly.com/sdk/features/storing-data/dynamodb#java).

## Quick setup

This assumes that you have already installed the LaunchDarkly Java SDK.

1. In DynamoDB, create a table which has the following schema: a partition key called "namespace" and a sort key called "key", both with a string type. The LaunchDarkly library does not create the table automatically, because it has no way of knowing what additional properties (such as permissions and throughput) you would want it to have.

2. Add this library to your project (substitute the [latest version](https://central.sonatype.com/artifact/com.launchdarkly/launchdarkly-java-server-sdk-dynamodb-store) number for `XXX`):

        <dependency>
          <groupId>com.launchdarkly</groupId>
          <artifactId>launchdarkly-java-server-sdk-dynamodb-store</artifactId>
          <version>XXX</version>
        </dependency>

3. If you do not already have the AWS SDK in your project, add the DynamoDB part of it. (This needs to be added separately, rather than being included in the LaunchDarkly jar, because AWS classes are exposed in the public interface.)

        <dependency>
          <groupId>software.amazon.awssdk</groupId>
          <artifactId>dynamodb</artifactId>
          <version>2.1.4</version>
        </dependency>

4. Import the LaunchDarkly package and the package for this library:

        import com.launchdarkly.sdk.server.*;
        import com.launchdarkly.sdk.server.integrations.*;

5. When configuring your SDK client, add the DynamoDB feature store:

        LDConfig config = new LDConfig.Builder()
            .dataStore(
                Components.persistentDataStore(
                    DynamoDb.dataStore("my-table-name")
                )
            )
            .build();

By default, the DynamoDB client will try to get your AWS credentials and region name from environment variables and/or local configuration files, as described in the AWS SDK documentation. There are methods in `DynamoDbDataStoreBuilder` for changing the configuration options. Alternatively, if you already have a fully configured DynamoDB client object, you can tell LaunchDarkly to use that:

                Components.persistentDataStore(
                    DynamoDb.dataStore("my-table-name").existingClient(myDynamoDbClientInstance)
                )

## Caching behavior

The LaunchDarkly SDK has a standard caching mechanism for any persistent data store, to reduce database traffic. This is configured through the SDK's `PersistentDataStoreBuilder` class as described the SDK documentation. For instance, to specify a cache TTL of 5 minutes:

        LDConfig config = new LDConfig.Builder()
            .dataStore(
                Components.persistentDataStore(
                    DynamoDb.dataStore("my-table-name")
                ).cacheTime(Duration.ofMinutes(5))
            )
            .build();

## Data size limitation

DynamoDB has [a 400KB limit](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/ServiceQuotas.html#limits-items) on the size of any data item. For the LaunchDarkly SDK, a data item consists of the JSON representation of an individual feature flag or segment configuration, plus a few smaller attributes. You can see the format and size of these representations by querying `https://sdk.launchdarkly.com/flags/latest-all` and setting the `Authorization` header to your SDK key.

Most flags and segments won't be nearly as big as 400KB, but they could be if for instance you have a long list of user keys for individual user targeting. If the flag or segment representation is too large, it cannot be stored in DynamoDB. To avoid disrupting storage and evaluation of other unrelated feature flags, the SDK will simply skip storing that individual flag or segment, and will log a message (at ERROR level) describing the problem. For example:

```
    The item "my-flag-key" in "features" was too large to store in DynamoDB and was dropped
```

If caching is enabled in your configuration, the flag or segment may still be available in the SDK from the in-memory cache, but do not rely on this. If you see this message, consider redesigning your flag/segment configurations, or else do not use DynamoDB for the environment that contains this data item.

This limitation does not apply to target lists in [Big Segments](https://docs.launchdarkly.com/home/users/big-segments/).

A future version of the LaunchDarkly DynamoDB integration may use different strategies to work around this limitation, such as compressing the data or dividing it into multiple items. However, this integration is required to be interoperable with the DynamoDB integrations used by all the other LaunchDarkly SDKs and by the Relay Proxy, so any such change will only be made as part of a larger cross-platform release.

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
