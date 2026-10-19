package com.bh4gzk.morserunner.contest

/**
 * The bundled contest documents, read from the classpath.
 *
 * **Why `core-contest/src/main/resources/contests/`** (the location decision T3 had to
 * record): the schema, its loader and its documents are one unit of work — a schema change
 * and the configs that exercise it move together, and `:core-contest:test` can validate
 * every config with no Android and no assets pipeline. `app` already depends on
 * `core-contest`, and a JVM module's `src/main/resources` are packaged into the APK and
 * readable through the class loader, so the app gets them for free.
 *
 * A manifest keeps discovery explicit instead of scanning the classpath: T3 ships three of
 * the twelve [ContestRegistry.bundledIds] and T10–T20 add the rest here, one ticket each.
 * A nested listing API that "finds whatever is on the classpath" would hide a missing
 * document; a manifest makes it a load error.
 */
object ContestLibrary {

    const val MANIFEST_PATH: String = "contests/manifest.json"
    const val CONFIG_DIRECTORY: String = "contests"

    /** Ids this build actually ships a document for. */
    const val MANIFEST_KEY: String = "configs"

    data class Bundled(val configs: List<ContestConfig>, val errors: List<ConfigError>)

    /** Loads every config named in the manifest. Errors are returned, never thrown. */
    fun bundled(): Bundled {
        val errors = mutableListOf<ConfigError>()
        val manifestText = readResource(MANIFEST_PATH)
        if (manifestText == null) {
            errors += ConfigError(MANIFEST_PATH, "", "missing classpath resource")
            return Bundled(emptyList(), errors)
        }
        val ids = when (val parsed = Json.parse(manifestText)) {
            is JsonParseResult.Failure -> {
                errors += ConfigError(MANIFEST_PATH, "", parsed.error.render())
                return Bundled(emptyList(), errors)
            }
            is JsonParseResult.Success -> {
                val root = parsed.value as? JsonValue.Obj
                val list = root?.get(MANIFEST_KEY) as? JsonValue.Arr
                list?.items?.mapNotNull { (it as? JsonValue.Str)?.value }
            }
        }
        if (ids == null || ids.isEmpty()) {
            errors += ConfigError(MANIFEST_PATH, MANIFEST_KEY, "the manifest must list at least one contest id")
            return Bundled(emptyList(), errors)
        }
        val configs = mutableListOf<ContestConfig>()
        ids.forEach { id ->
            when (val result = load(id)) {
                is LoadResult.Success -> configs += result.config
                is LoadResult.Failure -> errors += result.errors
            }
        }
        return Bundled(configs, errors)
    }

    /** Loads one contest by id, e.g. `load("cqww")`. */
    fun load(id: String): LoadResult {
        val path = "$CONFIG_DIRECTORY/$id.json"
        val text = readResource(path)
            ?: return LoadResult.Failure(listOf(ConfigError(path, "", "missing classpath resource")))
        return ContestLoader.load(path, text)
    }

    private fun readResource(path: String): String? =
        ContestLibrary::class.java.classLoader.getResourceAsStream(path)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
}
