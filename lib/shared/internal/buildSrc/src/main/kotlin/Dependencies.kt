
// Centralize dependencies here instead of writing them out in the top-level
// build script(s).

object Versions {
    const val gson = "2.8.9"
    const val launchdarklyJavaSdkCommon = "2.1.1"
    const val launchdarklyLogging = "1.1.1"
    const val okhttp = "4.9.1"
    const val testHelpers = "1.2.0"
}

object PluginVersions {
    const val nexusPublish = "1.3.0"
    const val nexusStaging = "0.30.0"
}

object Libs {
    val implementation = listOf<String>(
        // We would put anything here that we want to go into the Gradle "implementation"
        // configuration, if and only if we want those things to show up in pom.xml.
        "com.google.code.gson:gson:${Versions.gson}",
        "com.launchdarkly:launchdarkly-java-sdk-common:${Versions.launchdarklyJavaSdkCommon}",
        "com.launchdarkly:launchdarkly-logging:${Versions.launchdarklyLogging}",
        "com.squareup.okhttp3:okhttp:${Versions.okhttp}"
    )

    val javaTestImplementation = listOf(
        "junit:junit:4.12",
        "org.hamcrest:hamcrest-library:1.3",
        "com.google.guava:guava:32.0.1-jre"
        
        // "com.launchdarkly:test-helpers:${Versions.testHelpers}"
        // test-helpers is special-cased in build.gradle.kts and build-android.gradle
    )

    val androidTestImplementation = javaTestImplementation + listOf(    
        "androidx.test:core:1.4.0",
        "androidx.test:runner:1.4.0",
        "androidx.test:rules:1.4.0",
        "androidx.test.ext:junit:1.1.3"
    )

    val javaBuiltInGradlePlugins = listOf(
        "java",
        "java-library",
        "checkstyle",
        "signing",
        "maven-publish",
        "idea",
        "jacoco"
    )

    val javaExtGradlePlugins = mapOf(
        "io.github.gradle-nexus.publish-plugin" to PluginVersions.nexusPublish,
        "io.codearte.nexus-staging" to PluginVersions.nexusStaging
    )
}
