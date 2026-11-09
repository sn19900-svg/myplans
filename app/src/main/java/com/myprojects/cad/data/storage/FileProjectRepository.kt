package com.myprojects.cad.data.storage

import android.content.Context
import com.myprojects.cad.domain.model.CadProject
import com.myprojects.cad.domain.model.ProjectInfo
import com.myprojects.cad.domain.repository.ProjectRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One JSON file per project in the app's private storage. Writes go to a temp file first, then rename. */
@Singleton
class FileProjectRepository @Inject constructor(
    @ApplicationContext context: Context
) : ProjectRepository {

    private val dir = File(context.filesDir, "projects").apply { mkdirs() }
    private val lastFile = File(context.filesDir, "last_project.txt")

    private fun fileFor(id: String) = File(dir, id.filter { it.isLetterOrDigit() || it == '-' } + ".json")

    override suspend fun save(project: CadProject) {
        withContext(Dispatchers.IO) {
            val target = fileFor(project.id)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeText(ProjectJson.encode(project.copy(modifiedAt = System.currentTimeMillis())))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    override suspend fun load(id: String): CadProject? = withContext(Dispatchers.IO) {
        val f = fileFor(id)
        if (!f.exists()) null else runCatching { ProjectJson.decode(f.readText()) }.getOrNull()
    }

    override suspend fun list(): List<ProjectInfo> = withContext(Dispatchers.IO) {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
        files.mapNotNull { f -> runCatching { ProjectJson.info(f.readText()) }.getOrNull() }
            .sortedByDescending { it.modifiedAt }
    }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { fileFor(id).delete() }
    }

    override suspend fun lastOpenedId(): String? = withContext(Dispatchers.IO) {
        if (lastFile.exists()) lastFile.readText().trim().ifEmpty { null } else null
    }

    override suspend fun setLastOpened(id: String) {
        withContext(Dispatchers.IO) { lastFile.writeText(id) }
    }
}
