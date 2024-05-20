
// This build script controls the building of the shared Gradle code in
// buildSrc. Putting code under buildSrc allows us to break it up for better
// clarity, leaving a much simpler build script at the top level of the repo.

// For the java-sdk-common project, this also allows us to share some values
// between build.gradle.kts and build-android.gradle in a clearer way than
// the old method of including a shared build script.

// Things that are specific to this project, like dependencies, are in
// buildSrc/src/main/kotlin. Reusable helper code that isn't specific to this
// project is in buildSrc/src/main/kotlin/helpers.

plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}
