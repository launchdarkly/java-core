package helpers

import org.gradle.api.tasks.TaskProvider
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

// Jacoco.configureTasks provides reusable configuration logic for using the Jacoco
// test coverage plugin in a Java project. See also: TestCoverageOverrides.kt

object Jacoco {
    fun configureTasks(reportTask: TaskProvider<JacocoReport>,
        verificationTask: TaskProvider<JacocoCoverageVerification>) {
        reportTask.configure {
            reports {
                xml.required.set(true)
                csv.required.set(true)
                html.required.set(true)
            }
        }

        verificationTask.configure {
            // See notes in CONTRIBUTING.md on code coverage. Unfortunately we can't configure line-by-line code
            // coverage overrides within the source code itself, because Jacoco operates on bytecode.
            violationRules {
                TestCoverageOverrides.methodsWithMissedLineCount.forEach { signature, maxMissedLines ->
                    rule {
                        element = "METHOD"
                        includes = listOf(signature)
                        limit {
                            counter = "LINE"
                            value = "MISSEDCOUNT"
                            maximum = maxMissedLines.toBigDecimal()
                        }
                    }
                }
                
                // General rule that we should expect 100% test coverage; exclude any methods that
                // have overrides in TestCoverageOverrides.
                rule {
                    element = "METHOD"
                    limit {
                        counter = "LINE"
                        value = "MISSEDCOUNT"
                        maximum = 0.toBigDecimal()
                    }
                    excludes = TestCoverageOverrides.methodsWithMissedLineCount.map { it.key } +
                        TestCoverageOverrides.methodsToSkip
                }
            }
        }
    }
}
