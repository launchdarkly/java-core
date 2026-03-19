package helpers

import org.gradle.api.publish.maven.MavenPom

// Pom.standardPom provides reusable logic for setting the pom.xml properties
// of LaunchDarkly packages. It gets its values from ProjectValues.kt.

object Pom {
    fun standardPom(pom: MavenPom) {
        pom.name.set(ProjectValues.artifactId)
        pom.description.set(ProjectValues.description)
        pom.url.set("https://github.com/${ProjectValues.githubRepo}")
        pom.licenses {
            license {
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                name.set("The Apache License, Version 2.0")
            }
        }
        pom.developers {
            developer {
                name.set(ProjectValues.pomDeveloperName)
                email.set(ProjectValues.pomDeveloperEmail)
            }
        }
        pom.scm {
            connection.set("scm:git:git://github.com/${ProjectValues.githubRepo}.git")
            developerConnection.set("scm:git:ssh:git@github.com:${ProjectValues.githubRepo}.git")
            url.set("https://github.com/${ProjectValues.githubRepo}")
        }
    }
}
