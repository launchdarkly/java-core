package com.launchdarkly.sdk.internal;

/**
 * The only purpose of this class is to support the somewhat roundabout mechanism we use in CI to run
 * all of our unit tests in an Android environment too. All unit tests in this project should be either
 * directly or indirectly descended from this class. Then, when we run the Android tests, we replace
 * this class with another version (from src/androidTest/java) that has the necessary Android test
 * runner annotation on it.
 */
public abstract class BaseTest extends BaseInternalTest {
}
