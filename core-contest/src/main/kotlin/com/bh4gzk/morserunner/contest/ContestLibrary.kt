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
 * A manifest keeps discovery explicit instead of scanning the classpath: T3 shipped three of
 * the twelve [ContestRegistry.bundledIds] and T10–T18 added the rest here, one ticket each —
 * T18 (ARRL Field Day) was the last, so all twelve now ship a document.
 * A nested listing API that "finds whatever is on the classpath" would hide a missing
 * document; a manifest makes it a load error.
 *
 * **Two authorities, on purpose (T10).** [ContestRegistry] is the authority on the twelve
 * names a config may claim; this manifest is the authority on the documents **this build
 * actually ships**. The settings screen may only choose a contest the manifest lists, so a
 * stored id from an older build falls back to [DEFAULT_ID] instead of failing to start
 * (`SessionSettings.normalized`).
 */
object ContestLibrary {

    const val MANIFEST_PATH: String = "contests/manifest.json"
    const val CONFIG_DIRECTORY: String = "contests"

    /** Ids this build actually ships a document for. */
    const val MANIFEST_KEY: String = "configs"

    /** The contest a fresh settings set starts from: Milestone A's CQ WW (spec §11). */
    const val DEFAULT_ID: String = "cqww"

    data class Bundled(val configs: List<ContestConfig>, val errors: List<ConfigError>)

    /** One selectable contest: what the settings picker shows and what a session may start. */
    data class Entry(val id: String, val name: String)

    private data class Manifest(val ids: List<String>, val errors: List<ConfigError>)

    /** The manifest, parsed once. Errors are kept so [bundled] can report a broken manifest. */
    private val manifest: Manifest by lazy { readManifest() }

    /** The ids the manifest names, in manifest order (empty when the manifest is broken). */
    fun manifestIds(): List<String> = manifest.ids

    /** Loads every config named in the manifest. Errors are returned, never thrown. */
    fun bundled(): Bundled {
        val ids = manifest.ids
        if (ids.isEmpty()) return Bundled(emptyList(), manifest.errors)
        val errors = manifest.errors.toMutableList()
        val configs = mutableListOf<ContestConfig>()
        ids.forEach { id ->
            when (val result = load(id)) {
                is LoadResult.Success -> configs += result.config
                is LoadResult.Failure -> errors += result.errors
            }
        }
        return Bundled(configs, errors)
    }

    /**
     * The contests a session may start, in manifest order.
     *
     * A document that fails to load is **omitted** rather than shown: a contest that cannot
     * be loaded cannot be started, and the settings screen must not offer it. That the shipped
     * documents all load is proven by [bundled]'s callers (`ContestLibraryTest`,
     * `PackagedAssetsTest`), not by quietly hiding the error here.
     */
    fun catalog(): List<Entry> = catalogEntries

    private val catalogEntries: List<Entry> by lazy {
        manifest.ids.mapNotNull { id ->
            when (val result = load(id)) {
                is LoadResult.Success -> Entry(result.config.id, result.config.name)
                is LoadResult.Failure -> null
            }
        }
    }

    /** Whether this build ships a loadable document for [id]. */
    fun isAvailable(id: String): Boolean = catalog().any { it.id == id }

    /** [id] when this build ships it, [DEFAULT_ID] otherwise — never a session that cannot start. */
    fun normalizeId(id: String): String = if (isAvailable(id)) id else DEFAULT_ID

    /** Loads one contest by id, e.g. `load("cqww")`. */
    fun load(id: String): LoadResult {
        val path = "$CONFIG_DIRECTORY/$id.json"
        val text = readResource(path)
            ?: return LoadResult.Failure(listOf(ConfigError(path, "", "missing classpath resource")))
        return ContestLoader.load(path, text)
    }

    private fun readManifest(): Manifest {
        val text = readResource(MANIFEST_PATH)
            ?: return Manifest(emptyList(), listOf(ConfigError(MANIFEST_PATH, "", "missing classpath resource")))
        return when (val parsed = Json.parse(text)) {
            is JsonParseResult.Failure ->
                Manifest(emptyList(), listOf(ConfigError(MANIFEST_PATH, "", parsed.error.render())))
            is JsonParseResult.Success -> {
                val root = parsed.value as? JsonValue.Obj
                val list = root?.get(MANIFEST_KEY) as? JsonValue.Arr
                val ids = list?.items?.mapNotNull { (it as? JsonValue.Str)?.value }
                if (ids == null || ids.isEmpty()) {
                    Manifest(
                        emptyList(),
                        listOf(ConfigError(MANIFEST_PATH, MANIFEST_KEY, "the manifest must list at least one contest id")),
                    )
                } else {
                    Manifest(ids, emptyList())
                }
            }
        }
    }

    private fun readResource(path: String): String? =
        ContestLibrary::class.java.classLoader.getResourceAsStream(path)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
}
