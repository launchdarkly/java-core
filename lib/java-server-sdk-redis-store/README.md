# LaunchDarkly SDK for Java - Redis integration

This library provides a Redis-backed persistence mechanism (feature store) for the [LaunchDarkly Java SDK](https://github.com/launchdarkly/java-server-sdk), replacing the default in-memory feature store. The Redis API implementation it uses is [Jedis](https://github.com/xetorthio/jedis).

This version of the library requires at least version 6.0.0 of the LaunchDarkly Java SDK; for versions of the library to use with earlier SDK versions, see the changelog. The minimum Java version is 8.

For more information, see also: [Using Redis as a persistent feature store](https://docs.launchdarkly.com/sdk/features/storing-data/redis#java).

## Quick setup

This assumes that you have already installed the LaunchDarkly Java SDK.

1. Add this library to your project (substitute the latest version number for `XXX`):

        <dependency>
          <groupId>com.launchdarkly</groupId>
          <artifactId>launchdarkly-java-server-sdk-redis-store</artifactId>
          <version>XXX</version>
        </dependency>

2. The Redis client library (Jedis) should be pulled in automatically if you do not specify a dependency for it. If you want to use a different version, you may add your own dependency:

        <dependency>
          <groupId>redis.clients</groupId>
          <artifactId>jedis</artifactId>
          <version>2.9.0</version>
        </dependency>

    This library is compatible with Jedis 2.x versions greater than or equal to 2.9.0, and also with Jedis 3.x.

3. Import the LaunchDarkly package and the package for this library:

        import com.launchdarkly.sdk.server.*;
        import com.launchdarkly.sdk.server.integrations.*;

4. When configuring your SDK client, add the Redis data store as a `persistentDataStore`. You may specify any custom Redis options using the methods of `RedisDataStoreBuilder`. For instance, to customize the Redis URL:

        LDConfig config = new LDConfig.Builder()
            .dataStore(
                Components.persistentDataStore(
                    Redis.dataStore().url("redis://my-redis-host")
                )
            )
            .build();

By default, the store will try to connect to a local Redis instance on port 6379.

## Caching behavior

The LaunchDarkly SDK has a standard caching mechanism for any persistent data store, to reduce database traffic. This is configured through the SDK's `PersistentDataStoreBuilder` class as described the SDK documentation. For instance, to specify a cache TTL of 5 minutes:

        LDConfig config = new LDConfig.Builder()
            .dataStore(
                Components.persistentDataStore(
                    Redis.dataStore()
                ).cacheTime(Duration.ofMinutes(5))
            )
            .build();

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
