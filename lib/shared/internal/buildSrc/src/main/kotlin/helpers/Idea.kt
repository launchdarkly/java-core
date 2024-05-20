package helpers

import org.gradle.api.tasks.TaskProvider
import org.gradle.plugins.ide.idea.model.IdeaModel

// Idea.configure provides reusable configuration logic for the Idea
// behavior we normally use.

object Idea {
    fun configure(ideaModel: IdeaModel) {
        ideaModel.module {
        	isDownloadJavadoc = true
        	isDownloadSources = true
    	}
    }
}
