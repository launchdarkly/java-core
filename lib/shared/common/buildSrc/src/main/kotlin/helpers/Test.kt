package helpers

import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// Test.configureTask provides reusable configuration logic for the Java test
// behavior we normally use.

object Test {
    fun configureTask(compileTestTask: TaskProvider<JavaCompile>, testTask: TaskProvider<Test>,
        classpathConfig: Configuration?) {

        compileTestTask.configure {
            if (classpathConfig != null) {
                classpath += classpathConfig
            }
        }

        testTask.configure {
            testLogging {
                events("passed", "skipped", "failed", "standardOut", "standardError")
                showStandardStreams = true
                exceptionFormat = TestExceptionFormat.FULL
            }

            if (classpathConfig != null) {
                classpath += classpathConfig
            }
        }
    }
}
