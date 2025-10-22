import java.time.Duration
import org.gradle.external.javadoc.CoreJavadocOptions

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
    Libs.implementation.forEach { implementation(it) }
    Libs.javaTestImplementation.forEach { testImplementation(it) }
}

checkstyle {
    toolVersion = "9.3"
    configFile = file("${project.rootDir}/checkstyle.xml")
}

tasks.checkstyleMain {
    // Exclude embedded nanohttpd code from checkstyle
    exclude("com/launchdarkly/testhelpers/httptest/nanohttpd/**")
}

tasks.jar {
    manifest {
        attributes(mapOf("Implementation-Version" to project.version))
    }
    // Include NOTICE file in binary distribution
    from(".") {
        include("NOTICE")
        into("META-INF")
    }
    // Include nanohttpd license in binary distribution per BSD 3-Clause requirements
    from("src/main/java/com/launchdarkly/testhelpers/httptest/nanohttpd") {
        include("LICENSE.md")
        into("META-INF/licenses/nanohttpd")
    }
}

tasks.javadoc {
    // Force the Javadoc build to fail if there are any Javadoc warnings.
    (options as CoreJavadocOptions).addStringOption("Xwerror")

    // Exclude embedded nanohttpd code from Javadoc generation
    exclude("com/launchdarkly/testhelpers/httptest/nanohttpd/**")
}

helpers.Test.configureTask(tasks.compileTestJava, tasks.test, configurations["testRuntimeClasspath"])

helpers.Jacoco.configureTasks(
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
        sonatype{
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}

signing {
    setRequired({ findProperty("skipSigning") != "true" })
    sign(publishing.publications["mavenJava"])
}
