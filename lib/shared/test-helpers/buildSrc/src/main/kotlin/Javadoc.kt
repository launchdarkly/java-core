package helpers

import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.CoreJavadocOptions

object Javadoc {
    fun configureTask(javadocTask: TaskProvider<Javadoc>, classpathConfig: Configuration?) {
        javadocTask.configure {
            // Force the Javadoc build to fail if there are any Javadoc warnings. See: https://discuss.gradle.org/t/javadoc-fail-on-warning/18141/3
            // See JDK-8200363 (https://bugs.openjdk.java.net/browse/JDK-8200363)
            // for information about the -Xwerror option.
            (options as CoreJavadocOptions).addStringOption("Xwerror")

            if (classpathConfig != null) {
                classpath += classpathConfig
            }
        }
    }
}
