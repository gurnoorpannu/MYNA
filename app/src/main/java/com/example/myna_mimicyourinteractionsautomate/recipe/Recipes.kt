package com.example.myna_mimicyourinteractionsautomate.recipe

import java.io.File

/** Recipes on disk: files/recipes/<id>.json. ponytail: plain files, Room when we need queries. */
class Recipes(private val dir: File) {

    init { dir.mkdirs() }

    fun save(r: Recipe) = File(dir, "${r.id}.json").writeText(RecipeJson.encodeToString(r))

    fun all(): List<Recipe> = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.lastModified() }
        .mapNotNull { runCatching { RecipeJson.decodeFromString<Recipe>(it.readText()) }.getOrNull() }

    fun golden() = all().filter { it.golden }

    fun delete(id: String) = File(dir, "$id.json").delete()

    companion object {
        /** Until the compiler (Phase 4) exists: a recording replayed as-is, mistakes dropped. */
        fun fromRecording(r: Recording, golden: Boolean = false) = Recipe(
            id = "${r.app.substringAfterLast('.')}_${r.startedAt}",
            app = r.app,
            utterance = r.utterance,
            subtasks = listOf(Subtask("demo", steps = r.steps.filter { !it.noise })),
            end = if (r.stoppedBy == "safety_gate") End.PAYMENT_SCREEN else End.LAST_SCREEN,
            golden = golden,
        )
    }
}
