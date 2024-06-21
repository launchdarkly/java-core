import java.time.Duration

buildscript {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

plugins {  // see Dependencies.kt in buildSrc
    Libs.javaBuiltInGradlePlugins.forEach { id(it) }
    Libs.javaExtGradlePlugins.forEach { (n, v) -> id(n) version v }
}

repositories {
    mavenLocal()
    // Before LaunchDarkly release artifacts get synced to Maven Central they are here along with snapshots:
    maven { url = uri("https://oss.sonatype.org/content/groups/public/") }
    mavenCentral()
}

configurations.all {
    // check for updates every build for dependencies with: 'changing: true'
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

base {
    group = ProjectValues.groupId
    archivesBaseName = ProjectValues.artifactId
    version = version
}

java {
    withJavadocJar()
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {  // see Dependencies.kt in buildSrc
    Libs.implementation.forEach { api(it)}
    Libs.javaTestImplementation.forEach { testImplementation(it) }

    testImplementation("com.launchdarkly:test-helpers:${Versions.testHelpers}")
    // see build-android.gradle about the reason for special-casing this
}

checkstyle {
    toolVersion = "9.3"
    configFile = file("${project.rootDir}/checkstyle.xml")
}

helpers.Javadoc.configureTask(tasks.javadoc, null)  // see Javadoc.kt in buildSrc

helpers.Test.configureTask(tasks.compileTestJava, tasks.test, null)  // see Test.kt in buildSrc

helpers.Jacoco.configureTasks(  // see Jacoco.kt in buildSrc
    tasks.jacocoTestReport,
    tasks.jacocoTestCoverageVerification
)

helpers.Idea.configure(idea)

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            helpers.Pom.standardPom(pom)  // see Pom.kt in buildSrc
        }
    }
    repositories {
        mavenLocal()
    }
}

nexusPublishing {
    clientTimeout.set(Duration.ofMinutes(2)) // we've seen extremely long delays in creating repositories
    repositories {
        sonatype()
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}
