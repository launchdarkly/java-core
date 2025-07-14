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
    sourceCompatibility = JavaVersion.VERSION_1_7
    targetCompatibility = JavaVersion.VERSION_1_7
}

// See Dependencies.kt in buildSrc for the purpose of "privateImplementation"
val privateImplementation by configurations.creating

dependencies {  // see Dependencies.kt in buildSrc
    Libs.privateImplementation.forEach { privateImplementation(it)}
    Libs.javaTestImplementation.forEach { testImplementation(it) }
}

checkstyle {
    toolVersion = "9.3"
    configFile = file("${project.rootDir}/checkstyle.xml")
}

tasks.compileJava {
    // See note in Dependencies.kt in buildSrc on "privateImplementation"
    classpath = configurations["privateImplementation"]
}

helpers.Javadoc.configureTask(tasks.javadoc, configurations["privateImplementation"])  // see Javadoc.kt in buildSrc

helpers.Test.configureTask(tasks.compileTestJava, tasks.test,
    configurations["privateImplementation"])  // see Test.kt in buildSrc

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
