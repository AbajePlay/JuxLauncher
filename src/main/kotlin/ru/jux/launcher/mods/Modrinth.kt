package ru.jux.launcher.mods

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.jux.launcher.core.Json
import ru.jux.launcher.net.Http

object Modrinth {

    private const val API = "https://api.modrinth.com/v2"

    @Serializable
    data class Version(
        val id: String,
        @SerialName("project_id") val projectId: String,
        val name: String = "",
        @SerialName("version_number") val versionNumber: String = "",
        @SerialName("version_type") val versionType: String = "release",
        @SerialName("date_published") val datePublished: String = "",
        val files: List<VersionFile> = emptyList(),
        val dependencies: List<Dependency> = emptyList(),
    ) {
        val primaryFile: VersionFile? get() = files.firstOrNull { it.primary } ?: files.firstOrNull()
    }

    @Serializable
    data class VersionFile(
        val url: String,
        val filename: String,
        val primary: Boolean = false,
        val size: Long = 0,
        val hashes: Map<String, String> = emptyMap(),
    ) {
        val sha1: String? get() = hashes["sha1"]
    }

    @Serializable
    data class Dependency(
        @SerialName("project_id") val projectId: String? = null,
        @SerialName("version_id") val versionId: String? = null,
        @SerialName("dependency_type") val type: String = "required",
    )

    @Serializable
    private data class Project(val id: String, val title: String = "")

    fun versions(project: String, loader: String, gameVersion: String): List<Version> {
        val url = "$API/project/$project/version".toHttpUrl().newBuilder()
            .addQueryParameter("loaders", "[\"$loader\"]")
            .addQueryParameter("game_versions", "[\"$gameVersion\"]")
            .build()
            .toString()
        return Json.decodeFromString<List<Version>>(Http.getString(url))
            .sortedByDescending { it.datePublished }
    }

    fun pick(versions: List<Version>): Version? =
        versions.firstOrNull { it.versionType == "release" }
            ?: versions.firstOrNull { it.versionType == "beta" }

    fun versionsByHash(sha1s: Collection<String>): Map<String, Version> {
        if (sha1s.isEmpty()) return emptyMap()
        val body = buildJsonObject {
            put("hashes", JsonArray(sha1s.map { JsonPrimitive(it) }))
            put("algorithm", "sha1")
        }
        return Json.decodeFromString<Map<String, Version>>(Http.postJson("$API/version_files", body.toString()))
    }

    fun titles(ids: Collection<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val url = "$API/projects".toHttpUrl().newBuilder()
            .addQueryParameter("ids", ids.joinToString(",", "[", "]") { "\"$it\"" })
            .build()
            .toString()
        return Json.decodeFromString<List<Project>>(Http.getString(url)).associate { it.id to it.title }
    }
}
