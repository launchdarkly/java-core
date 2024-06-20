
// Centralize dependencies here instead of writing them out in the top-level
// build script(s).

object Versions {
    const val gson = "2.8.9"
    const val jacksonCore = "2.10.5"
    const val jacksonDatabind = "2.10.5.1"
}

object PluginVersions {
    const val nexusPublish = "1.3.0"
}

object Libs {
    val implementation = listOf<String>(
        // We would put anything here that we want to go into the Gradle "implementation"
        // configuration, if and only if we want those things to show up in pom.xml.
    )

    val javaTestImplementation = listOf(
        "org.hamcrest:hamcrest-library:1.3",
        "junit:junit:4.12"
    )

    val androidTestImplementation = javaTestImplementation + listOf(
        "com.android.support.test:runner:1.0.2"
    )

    val privateImplementation = listOf(
        // These will be used in the compile-time classpath, but they should *not* be put in
        // the usual Gradle "implementation" configuration, because we don't want them to be
        // visible at all in the module's published dependencies - not even in "runtime" scope.
        // Here's why:
        //
        // 1. For Gson: While java-sdk-common does need Gson in order to work, the
        // LaunchDarkly SDKs that use java-sdk-common have different strategies for packaging
        // Gson. The Android SDK exposes it as a regular dependency; the Java server-side SDK
        // embeds and shades Gson and does not expose it as a dependency. So we are leaving
        // it up to the SDK to provide Gson in some way.
        //
        // 2. For Jackson: The SDKs do not use, require, or embed Jackson; we provide the
        // LDJackson class as a convenience for applications that do use Jackson, and it is
        // only usable if the application already has Jackson in its classpath. So we do not
        // want Jackson to show up as a transitive dependency.

        "com.google.code.gson:gson:${Versions.gson}",
        "com.fasterxml.jackson.core:jackson-core:${Versions.jacksonCore}",
        "com.fasterxml.jackson.core:jackson-databind:${Versions.jacksonDatabind}"
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
        "io.github.gradle-nexus.publish-plugin" to PluginVersions.nexusPublish
    )
}
