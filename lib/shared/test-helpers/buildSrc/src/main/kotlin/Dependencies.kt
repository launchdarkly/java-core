
// Centralize dependencies here instead of writing them out in the top-level
// build script(s).

object Versions {
    const val gson = "2.7"
    const val guava = "32.0.1-jre"
    const val okhttpTls = "4.8.1"
    const val hamcrest = "1.3"
    const val okhttp = "4.5.0"
    const val junit = "4.12"
}

object PluginVersions {
    const val nexusPublish = "1.3.0"
}

object Libs {
    val implementation = listOf(
        "com.google.code.gson:gson:${Versions.gson}",
        "com.google.guava:guava:${Versions.guava}",
        "com.squareup.okhttp3:okhttp-tls:${Versions.okhttpTls}",
        "org.hamcrest:hamcrest-library:${Versions.hamcrest}"
    )

    val javaTestImplementation = listOf(
        "com.squareup.okhttp3:okhttp:${Versions.okhttp}",
        "junit:junit:${Versions.junit}"
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
