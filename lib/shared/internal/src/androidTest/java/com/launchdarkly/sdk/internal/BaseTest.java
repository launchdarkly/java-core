package com.launchdarkly.sdk.internal;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.runner.RunWith;

/**
 * When running our unit tests in Android, we substitute this version of BaseTest which provides
 * the correct test runner.
 */
@RunWith(AndroidJUnit4.class)
public abstract class BaseTest extends BaseInternalTest {
}
