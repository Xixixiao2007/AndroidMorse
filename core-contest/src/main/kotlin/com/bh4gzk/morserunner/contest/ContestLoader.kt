package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.data.ArrlSections
import com.bh4gzk.morserunner.expr.CompileResult
import com.bh4gzk.morserunner.expr.Expression
import com.bh4gzk.morserunner.expr.Value
import com.bh4gzk.morserunner.expr.compileExpression
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * One reason a contest document was rejected: which file, which field, and what.
 *
 * [render] is what the contest editor shows — `cqww.json: scoring.points — 1:24 — unknown
 * function 'foo'`. Expression errors keep the `line:col` spec §5 requires.
 */
data class ConfigError(val source: String, val path: String, val message: String) {
    fun render(): String = if (path.isEmpty()) "$source: $message" else "$source: $path \u2014 $message"
    override fun toString(): String = render()
}

sealed interface LoadResult {
    data class Success(val config: ContestConfig) : LoadResult

    /** Every problem found, not just the first — a config author fixes them in one pass. */
    data class Failure(val errors: List<ConfigError>) : LoadResult
}

/**
 * Turns a contest JSON document into a [ContestConfig], or into the list of
 * reasons it is not one (spec §4 "Load-time validation").
 *
 * The checks are, in order: JSON syntax ([Json]), JSON shape (unknown/missing/
 * wrong-typed fields), cross-field consistency (a binding names a real history
 * column, a parse group names a real field, an enum ref names a list constant),
 * then **expression compilation** and a static identifier check against the
 * environment the engine will actually provide. Nothing is deferred to QSO time.
 */
object ContestLoader {
    fun load(source: String, text: String): LoadResult = Loader(source).load(text)
}

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** Java named groups, e.g. `(?<sect>...)`; `(?<=` and `(?<!` are look-behinds, not names. */
private val NAMED_GROUP = Regex("\\(\\?<([A-Za-z][A-Za-z0-9]*)>")

/** Station properties the engine resolves before scoring a QSO (spec §4). */
private val STATION_IDENTIFIERS = setOf("myCall", "myCountry", "myContinent", "myZone", "mySection")

/**
 * Constants whose value is a **shared data asset** (spec §9), resolved from
 * `core-data` instead of being copied into the contest document.
 *
 * The ARRL/RAC section table is the first one: keeping 85 strings in `arrlss.json`
 * while `ArrlSections.all` ships the same 85 was T3's one open debt. The loader
 * now injects the asset, and a literal list under one of these names is rejected,
 * so the second copy cannot come back by accident (ADR-0004 decision 9).
 */
private val SHARED_DATA_ASSETS: Map<String, () -> List<String>> = mapOf(
    "arrlSections" to { ArrlSections.all },
)

private class Loader(private val source: String) {

    private val errors = mutableListOf<ConfigError>()

    fun load(text: String): LoadResult {
        val root = when (val parsed = Json.parse(text)) {
            is JsonParseResult.Failure -> {
                error("", parsed.error.render())
                return LoadResult.Failure(errors.toList())
            }
            is JsonParseResult.Success -> parsed.value
        }
        val obj = root as? JsonValue.Obj
            ?: return LoadResult.Failure(listOf(ConfigError(source, "", "the document root must be an object")))
        val config = build(obj)
        return if (config == null || errors.isNotEmpty()) {
            LoadResult.Failure(errors.toList())
        } else {
            LoadResult.Success(config)
        }
    }

    private fun build(root: JsonValue.Obj): ContestConfig? {
        onlyKeys(
            root, "",
            setOf(
                "schemaVersion", "id", "name", "duration", "runModes", "constants",
                "exchange", "validation", "scoring", "callHistory", "station",
                "messages", "capabilities",
            ),
        )
        val schemaVersion = root.int("schemaVersion", "") ?: -1
        if (schemaVersion != ContestConfig.SUPPORTED_SCHEMA_VERSION && schemaVersion != -1) {
            error("schemaVersion", "unsupported schema version $schemaVersion, expected ${ContestConfig.SUPPORTED_SCHEMA_VERSION}")
        }
        val id = root.str("id", "") ?: ""
        if (id.isNotEmpty() && id !in ContestRegistry.bundledIds()) {
            error("id", "'$id' is not one of the bundled contest ids")
        }
        val name = root.str("name", "") ?: ""
        val duration = root.obj("duration", "")?.let { readDuration(it) }
        val runModes = readRunModes(root)
        val constants = readConstants(root)
        val callHistory = root.obj("callHistory", "")?.let { readCallHistory(it) }
        val exchange = root.obj("exchange", "")?.let { readExchange(it, constants, callHistory) }
        val validation = readValidation(root, constants, exchange?.fieldKeys ?: emptySet())
        val scoring = root.obj("scoring", "")?.let { readScoring(it, constants, exchange) }
        val station = root.obj("station", "")?.let { readStation(it, constants) }
        val messages = readMessages(root)
        val capabilities = root.obj("capabilities", "")?.let { readCapabilities(it) }

        if (duration == null || callHistory == null || exchange == null || scoring == null ||
            station == null || capabilities == null
        ) {
            return null
        }
        return ContestConfig(
            schemaVersion = schemaVersion,
            id = id,
            name = name,
            duration = duration,
            runModes = runModes,
            constants = constants,
            exchange = exchange,
            validation = validation,
            scoring = scoring,
            callHistory = callHistory,
            station = station,
            messages = messages,
            capabilities = capabilities,
        )
    }

    // --- document sections ----------------------------------------------------

    private fun readDuration(obj: JsonValue.Obj): Duration? {
        val path = "duration"
        onlyKeys(obj, path, setOf("default", "min", "max"))
        val mark = errors.size
        val default = obj.int("default", path) ?: 0
        val min = obj.int("min", path) ?: 0
        val max = obj.int("max", path) ?: 0
        if (min < 1) error(child(path, "min"), "must be at least 1 minute")
        if (min > default || default > max) {
            error(path, "expected min <= default <= max, got $min <= $default <= $max")
        }
        return if (errors.size == mark) Duration(default, min, max) else null
    }

    private fun readRunModes(root: JsonValue.Obj): List<String> {
        val path = "runModes"
        val arr = root.arr(path, "") ?: return emptyList()
        val modes = arr.items.mapIndexedNotNull { index, item ->
            val itemPath = "$path[$index]"
            val mode = stringAt(item, itemPath) ?: return@mapIndexedNotNull null
            if (mode !in ContestConfig.RUN_MODES) {
                error(itemPath, "unknown run mode '$mode' (expected ${ContestConfig.RUN_MODES.sorted().joinToString("|")})")
            }
            mode
        }
        if (modes.isEmpty()) error(path, "at least one run mode is required")
        return modes
    }

    private fun readConstants(root: JsonValue.Obj): Map<String, Value> {
        val obj = root.obj("constants", "", required = false) ?: return emptyMap()
        val out = LinkedHashMap<String, Value>()
        obj.entries.forEach { (name, value) ->
            val path = child("constants", name)
            if (!IDENTIFIER.matches(name)) error(path, "'$name' is not a valid identifier")
            when {
                // `{ "asset": "arrlSections" }`: resolve the shared table.
                value is JsonValue.Obj -> readAssetConstant(value, path)?.let { out[name] = it }
                // A literal copy under a shared asset's name defeats the single source.
                name in SHARED_DATA_ASSETS -> error(
                    path,
                    "'$name' is a shared data asset (core-data); reference it as " +
                        "{ \"asset\": \"$name\" } instead of copying it",
                )
                else -> toValue(value, path)?.let { out[name] = it }
            }
        }
        return out
    }

    /** Resolves `{ "asset": "<id>" }` against [SHARED_DATA_ASSETS] (spec §9, ADR-0004 §9). */
    private fun readAssetConstant(obj: JsonValue.Obj, path: String): Value.List? {
        onlyKeys(obj, path, setOf("asset"))
        val id = obj.str("asset", path) ?: return null
        val provider = SHARED_DATA_ASSETS[id]
        if (provider == null) {
            error(
                child(path, "asset"),
                "unknown data asset '$id' (known: ${SHARED_DATA_ASSETS.keys.sorted().joinToString(", ")})",
            )
            return null
        }
        return Value.list(provider().map { Value.str(it) })
    }

    private fun toValue(value: JsonValue, path: String): Value? = when (value) {
        is JsonValue.Num -> Value.Num(value.value)
        is JsonValue.Str -> Value.Str(value.value)
        is JsonValue.Bool -> Value.Bool(value.value)
        is JsonValue.Arr -> {
            val items = value.items.map { toValue(it, path) }
            if (items.any { it == null }) null else Value.List(items.map { it!! })
        }
        is JsonValue.Obj -> {
            error(path, "an expression constant must be a number, string, boolean or list")
            null
        }
        is JsonValue.Null -> {
            error(path, "the expression language has no null value")
            null
        }
    }

    private fun readCallHistory(obj: JsonValue.Obj): CallHistory? {
        val path = "callHistory"
        onlyKeys(
            obj, path,
            setOf("asset", "delimiter", "skipPrefixes", "header", "minFields", "columns", "dedupe", "requiresMyCallInDxcc"),
        )
        val mark = errors.size
        val asset = obj.str("asset", path) ?: ""
        val delimiterText = obj.str("delimiter", path) ?: ""
        if (delimiterText.length != 1) error(child(path, "delimiter"), "must be a single character")
        val delimiter = delimiterText.firstOrNull() ?: ' '
        val skipPrefixes = obj.strings("skipPrefixes", path, required = false) ?: emptyList()
        val header = obj.obj("header", path, required = false)?.let { readHeader(it, child(path, "header")) }
        val minFields = obj.int("minFields", path) ?: 0
        if (minFields < 1) error(child(path, "minFields"), "must be at least 1")
        val columns = readColumns(obj.arr("columns", path), child(path, "columns"), header)
        val dedupe = obj.bool("dedupe", path, required = false) ?: false
        val requiresMyCallInDxcc = obj.bool("requiresMyCallInDxcc", path, required = false) ?: false
        if (columns.isEmpty()) error(child(path, "columns"), "at least one column is required")
        return if (errors.size == mark) {
            CallHistory(asset, delimiter, skipPrefixes, header, minFields, columns, dedupe, requiresMyCallInDxcc)
        } else {
            null
        }
    }

    private fun readHeader(obj: JsonValue.Obj, path: String): HeaderSpec? {
        onlyKeys(obj, path, setOf("marker", "names"))
        val mark = errors.size
        val marker = obj.str("marker", path) ?: ""
        val namesNode = obj.obj("names", path)?.entries ?: emptyMap()
        val names = namesNode.mapValues { (key, value) -> stringAt(value, child(child(path, "names"), key)) ?: "" }
        if (names.isEmpty()) error(child(path, "names"), "at least one column name is required")
        return if (errors.size == mark) HeaderSpec(marker, names) else null
    }

    private fun readColumns(node: JsonValue.Arr?, path: String, header: HeaderSpec?): List<HistoryColumn> {
        if (node == null) {
            error(path, "missing required array")
            return emptyList()
        }
        val columns = node.items.mapIndexedNotNull { index, item ->
            val itemPath = "$path[$index]"
            val obj = objAt(item, itemPath) ?: return@mapIndexedNotNull null
            onlyKeys(obj, itemPath, setOf("name", "index", "type", "required"))
            val name = obj.str("name", itemPath) ?: ""
            val indexValue = obj.int("index", itemPath, required = false)
            val typeText = obj.str("type", itemPath) ?: ""
            val type = ColumnType.entries.firstOrNull { it.name.equals(typeText, ignoreCase = true) }
            if (type == null) error(child(itemPath, "type"), "unknown column type '$typeText'")
            val required = obj.bool("required", itemPath, required = false) ?: false
            if (indexValue == null && header == null) {
                error(itemPath, "a positional column needs an 'index'")
            }
            if (indexValue != null && header != null) {
                error(itemPath, "a header-driven column must not declare an 'index'")
            }
            if (header != null && name !in header.names) {
                error(itemPath, "column '$name' is not listed in header.names")
            }
            if (indexValue != null && indexValue < 0) {
                error(child(itemPath, "index"), "must not be negative")
            }
            HistoryColumn(name, indexValue, type ?: ColumnType.TEXT, required)
        }
        columns.groupBy { it.name }.filter { it.value.size > 1 }.keys.forEach {
            error(path, "duplicate column name '$it'")
        }
        columns.filter { it.name.isEmpty() }.forEach { error(path, "a column needs a name") }
        return columns
    }

    private fun readExchange(
        obj: JsonValue.Obj,
        constants: Map<String, Value>,
        history: CallHistory?,
    ): Exchange? {
        val path = "exchange"
        onlyKeys(obj, path, setOf("myFields", "entryParse", "sent", "their"))
        val mark = errors.size
        val myFields = readFields(obj.arr("myFields", path), child(path, "myFields"))
        if (myFields.isEmpty()) error(child(path, "myFields"), "at least one exchange field is required")
        val fieldKeys = myFields.map { it.key }.toSet()
        val entryParse = obj.obj("entryParse", path, required = false)
            ?.let { readParse(it, child(path, "entryParse"), fieldKeys, "exchange field") }
        val sent = obj.obj("sent", path, required = false)
            ?.let { readSent(it, child(path, "sent"), constants.keys + fieldKeys) }
        val their = obj.obj("their", path)?.let { readTheir(it, child(path, "their"), constants, history) }
        if (errors.size != mark || their == null) return null
        return Exchange(myFields, entryParse, sent, their)
    }

    private fun readFields(node: JsonValue.Arr?, path: String): List<ExchangeField> {
        if (node == null) {
            error(path, "missing required array")
            return emptyList()
        }
        val fields = node.items.mapIndexedNotNull { index, item ->
            val itemPath = "$path[$index]"
            val obj = objAt(item, itemPath) ?: return@mapIndexedNotNull null
            onlyKeys(obj, itemPath, setOf("key", "type", "label", "send", "range", "values", "valuesRef"))
            val key = obj.str("key", itemPath) ?: ""
            if (key.isNotEmpty() && !IDENTIFIER.matches(key)) error(child(itemPath, "key"), "'$key' is not a valid identifier")
            val typeText = obj.str("type", itemPath) ?: ""
            val type = FieldType.entries.firstOrNull { it.name.equals(typeText, ignoreCase = true) }
            if (type == null) {
                error(child(itemPath, "type"), "unknown field type '$typeText'")
                return@mapIndexedNotNull null
            }
            val range = obj["range"]?.let { readRange(it, child(itemPath, "range")) }
            val values = obj["values"]?.let { stringListAt(it, child(itemPath, "values")) }
            val valuesRef = obj.str("valuesRef", itemPath, required = false)
            if (values != null && valuesRef != null) {
                error(itemPath, "give either 'values' or 'valuesRef', not both")
            }
            if (type == FieldType.ENUM && values == null && valuesRef == null) {
                error(itemPath, "an enum field needs 'values' or 'valuesRef'")
            }
            if (range != null && !type.numeric) {
                error(child(itemPath, "range"), "a range only applies to a numeric field")
            }
            ExchangeField(
                key = key,
                type = type,
                label = obj.str("label", itemPath, required = false),
                send = obj.str("send", itemPath, required = false),
                range = range,
                values = values,
                valuesRef = valuesRef,
            )
        }
        fields.groupBy { it.key }.filter { it.value.size > 1 }.keys.forEach {
            error(path, "duplicate field key '$it'")
        }
        return fields
    }

    private fun readRange(node: JsonValue, path: String): IntRange? {
        val arr = arrAt(node, path) ?: return null
        if (arr.items.size != 2) {
            error(path, "a range is exactly two numbers")
            return null
        }
        val from = intAt(arr.items[0], "$path[0]") ?: return null
        val to = intAt(arr.items[1], "$path[1]") ?: return null
        if (from > to) {
            error(path, "a range must be ascending")
            return null
        }
        return from..to
    }

    private fun readParse(
        obj: JsonValue.Obj,
        path: String,
        allowedGroups: Set<String>?,
        groupKind: String,
    ): ParseSpec? {
        onlyKeys(obj, path, setOf("pattern"))
        val mark = errors.size
        val text = obj.str("pattern", path) ?: return null
        val regex = try {
            Pattern.compile(text)
        } catch (exception: PatternSyntaxException) {
            error(child(path, "pattern"), "invalid regular expression: ${exception.description}")
            return null
        }
        val groups = NAMED_GROUP.findAll(text).map { it.groupValues[1] }.toList()
        if (groups.isEmpty()) error(child(path, "pattern"), "the pattern declares no named groups")
        groups.forEach { group ->
            if (!IDENTIFIER.matches(group)) error(child(path, "pattern"), "'$group' is not a valid group name")
            if (allowedGroups != null && group !in allowedGroups) {
                error(child(path, "pattern"), "group '$group' has no matching $groupKind")
            }
        }
        if (groups.size != groups.toSet().size) error(child(path, "pattern"), "duplicate group name")
        return if (errors.size == mark) ParseSpec(text, groups, regex) else null
    }

    private fun readSent(obj: JsonValue.Obj, path: String, allowed: Set<String>): SentExchange? {
        onlyKeys(obj, path, setOf("exch1", "exch2"))
        val mark = errors.size
        val exch1 = obj.str("exch1", path)?.let { compile(it, child(path, "exch1"), allowed) }
        val exch2 = obj.str("exch2", path)?.let { compile(it, child(path, "exch2"), allowed) }
        if (errors.size != mark || exch1 == null || exch2 == null) return null
        return SentExchange(exch1, exch2)
    }

    private fun readTheir(
        obj: JsonValue.Obj,
        path: String,
        constants: Map<String, Value>,
        history: CallHistory?,
    ): TheirExchange? {
        onlyKeys(obj, path, setOf("dice", "bindings", "derive", "parse", "exch1", "exch2"))
        val mark = errors.size
        val dice = obj.strings("dice", path, required = false) ?: emptyList()
        dice.forEach { if (!IDENTIFIER.matches(it)) error(child(path, "dice"), "'$it' is not a valid identifier") }
        if (dice.size != dice.toSet().size) error(child(path, "dice"), "duplicate dice name")
        val bindings = obj.obj("bindings", path, required = false)
            ?.let { readBindings(it, child(path, "bindings"), history) }
            ?: emptyMap()
        val derive = obj.obj("derive", path, required = false)
            ?.let { readDerive(it, child(path, "derive"), constants.keys + dice + bindings.keys) }
            ?: emptyMap()
        val parse = obj.obj("parse", path, required = false)
            ?.let { readParse(it, child(path, "parse"), null, "piece") }
        val allowed = constants.keys + dice + bindings.keys + derive.keys
        val exch1 = obj.str("exch1", path)?.let { compile(it, child(path, "exch1"), allowed) }
        val exch2 = obj.str("exch2", path)?.let { compile(it, child(path, "exch2"), allowed) }
        if (errors.size != mark || exch1 == null || exch2 == null) return null
        return TheirExchange(dice, bindings, derive, parse, exch1, exch2)
    }

    private fun readBindings(
        obj: JsonValue.Obj,
        path: String,
        history: CallHistory?,
    ): Map<String, TheirBinding> {
        val out = LinkedHashMap<String, TheirBinding>()
        val knownColumns = history?.columns?.map { it.name }?.toSet() ?: emptySet()
        obj.entries.forEach { (name, value) ->
            val itemPath = child(path, name)
            if (!IDENTIFIER.matches(name)) error(itemPath, "'$name' is not a valid identifier")
            val binding = objAt(value, itemPath) ?: return@forEach
            onlyKeys(binding, itemPath, setOf("source", "column"))
            when (val bindingSource = binding.str("source", itemPath)) {
                "history" -> {
                    val column = binding.str("column", itemPath) ?: return@forEach
                    if (column !in knownColumns) {
                        error(child(itemPath, "column"), "'$column' is not a call-history column")
                    }
                    out[name] = TheirBinding.HistoryColumn(column)
                }
                "serial" -> {
                    if (binding["column"] != null) {
                        error(child(itemPath, "column"), "a serial binding has no column")
                    }
                    out[name] = TheirBinding.Serial
                }
                null -> Unit
                else -> error(
                    child(itemPath, "source"),
                    "unknown binding source '$bindingSource' (expected history|serial)",
                )
            }
        }
        return out
    }

    private fun readDerive(
        obj: JsonValue.Obj,
        path: String,
        baseEnvironment: Set<String>,
    ): Map<String, Expression> {
        val out = LinkedHashMap<String, Expression>()
        val available = baseEnvironment.toMutableSet()
        obj.entries.forEach { (name, value) ->
            val itemPath = child(path, name)
            if (!IDENTIFIER.matches(name)) error(itemPath, "'$name' is not a valid identifier")
            val text = stringAt(value, itemPath) ?: return@forEach
            compile(text, itemPath, available)?.let { out[name] = it }
            available += name
        }
        return out
    }

    private fun readValidation(
        root: JsonValue.Obj,
        constants: Map<String, Value>,
        fieldKeys: Set<String>,
    ): List<ValidationRule> {
        val path = "validation"
        val arr = root.arr(path, "", required = false) ?: return emptyList()
        val allowed = constants.keys + fieldKeys
        return arr.items.mapIndexedNotNull { index, item ->
            val itemPath = "$path[$index]"
            val obj = objAt(item, itemPath) ?: return@mapIndexedNotNull null
            onlyKeys(obj, itemPath, setOf("field", "when", "error"))
            val field = obj.str("field", itemPath, required = false)
            if (field != null && field !in fieldKeys) {
                error(child(itemPath, "field"), "'$field' is not an exchange field")
            }
            val whenText = obj.str("when", itemPath) ?: return@mapIndexedNotNull null
            val message = obj.str("error", itemPath) ?: return@mapIndexedNotNull null
            val expression = compile(whenText, child(itemPath, "when"), allowed)
                ?: return@mapIndexedNotNull null
            ValidationRule(field, expression, message)
        }
    }

    private fun readScoring(
        obj: JsonValue.Obj,
        constants: Map<String, Value>,
        exchange: Exchange?,
    ): Scoring? {
        val path = "scoring"
        onlyKeys(obj, path, setOf("points", "multipliers"))
        val mark = errors.size
        val parseGroups = exchange?.their?.parse?.groupNames
            ?.map { TheirExchange.parseIdentifier(it) }
            ?.toSet()
            ?: emptySet()
        val allowed = constants.keys + (exchange?.fieldKeys ?: emptySet()) +
            setOf("theirCall", "theirExch1", "theirExch2") + STATION_IDENTIFIERS + parseGroups
        val points = obj.str("points", path)?.let { compile(it, child(path, "points"), allowed) }
        val multipliers = readMultipliers(obj.arr("multipliers", path), child(path, "multipliers"), allowed)
        if (errors.size != mark || points == null) return null
        return Scoring(points, multipliers)
    }

    private fun readMultipliers(
        node: JsonValue.Arr?,
        path: String,
        allowed: Set<String>,
    ): List<Multiplier> {
        if (node == null) {
            error(path, "missing required array")
            return emptyList()
        }
        if (node.items.isEmpty()) error(path, "at least one multiplier is required")
        return node.items.mapIndexedNotNull { index, item ->
            val itemPath = "$path[$index]"
            val obj = objAt(item, itemPath) ?: return@mapIndexedNotNull null
            onlyKeys(obj, itemPath, setOf("key", "when"))
            val key = obj.str("key", itemPath)
                ?.let { compile(it, child(itemPath, "key"), allowed) }
                ?: return@mapIndexedNotNull null
            val whenExpression = obj.str("when", itemPath, required = false)
                ?.let { compile(it, child(itemPath, "when"), allowed) }
            Multiplier(key, whenExpression)
        }
    }

    private fun readStation(obj: JsonValue.Obj, constants: Map<String, Value>): Station? {
        val path = "station"
        onlyKeys(obj, path, setOf("callPool", "speedRange", "exchangeDefaults"))
        val mark = errors.size
        val callPool = obj.str("callPool", path) ?: ""
        val rangeObj = obj.obj("speedRange", path)
        val allowed = constants.keys + "speed"
        val range = rangeObj?.let { readSpeedRange(it, child(path, "speedRange"), allowed) }
        val defaults = obj.obj("exchangeDefaults", path, required = false)?.entries?.mapValues { (key, value) ->
            stringAt(value, child(child(path, "exchangeDefaults"), key)) ?: ""
        } ?: emptyMap()
        if (errors.size != mark || range == null) return null
        return Station(callPool, range, defaults)
    }

    private fun readSpeedRange(obj: JsonValue.Obj, path: String, allowed: Set<String>): SpeedRange? {
        onlyKeys(obj, path, setOf("min", "max"))
        val mark = errors.size
        val min = obj.str("min", path)?.let { compile(it, child(path, "min"), allowed) }
        val max = obj.str("max", path)?.let { compile(it, child(path, "max"), allowed) }
        if (errors.size != mark || min == null || max == null) return null
        return SpeedRange(min, max)
    }

    private fun readMessages(root: JsonValue.Obj): Map<String, String> {
        val obj = root.obj("messages", "", required = false) ?: return emptyMap()
        return obj.entries.mapValues { (key, value) -> stringAt(value, child("messages", key)) ?: "" }
    }

    private fun readCapabilities(obj: JsonValue.Obj): Capabilities? {
        val path = "capabilities"
        onlyKeys(obj, path, setOf("farnsworth", "requiresMyDxcc"))
        val farnsworth = obj.bool("farnsworth", path, required = false) ?: false
        val requiresMyDxcc = obj.bool("requiresMyDxcc", path, required = false) ?: false
        return Capabilities(farnsworth, requiresMyDxcc)
    }

    // --- expressions ----------------------------------------------------------

    /**
     * Compiles [text] and checks its free identifiers against [allowed] — the
     * environment the engine promises for this particular field (spec §4).
     */
    private fun compile(text: String, path: String, allowed: Set<String>): Expression? =
        when (val result = compileExpression(text)) {
            is CompileResult.Failure -> {
                error(path, result.error.render())
                null
            }
            is CompileResult.Success -> {
                val unknown = result.expression.identifiers() - allowed
                if (unknown.isNotEmpty()) {
                    error(
                        path,
                        "unknown identifier ${unknown.joinToString(", ") { "'$it'" }} " +
                            "(this field sees ${allowed.sorted().joinToString(", ")})",
                    )
                }
                result.expression
            }
        }

    // --- reading primitives ---------------------------------------------------

    private fun error(path: String, message: String) {
        errors += ConfigError(source, path, message)
    }

    private fun child(path: String, key: String): String = if (path.isEmpty()) key else "$path.$key"

    private fun onlyKeys(obj: JsonValue.Obj, path: String, allowed: Set<String>) {
        obj.entries.keys.forEach { key ->
            if (key !in allowed) {
                error(child(path, key), "unknown field (expected one of: ${allowed.sorted().joinToString(", ")})")
            }
        }
    }

    private fun describe(value: JsonValue): String = when (value) {
        is JsonValue.Obj -> "an object"
        is JsonValue.Arr -> "an array"
        is JsonValue.Str -> "a string"
        is JsonValue.Num -> "a number"
        is JsonValue.Bool -> "a boolean"
        is JsonValue.Null -> "null"
    }

    private fun objAt(value: JsonValue?, path: String): JsonValue.Obj? {
        if (value == null) {
            error(path, "missing required object")
            return null
        }
        val obj = value as? JsonValue.Obj
        if (obj == null) error(path, "expected an object, found ${describe(value)}")
        return obj
    }

    private fun arrAt(value: JsonValue?, path: String): JsonValue.Arr? {
        if (value == null) {
            error(path, "missing required array")
            return null
        }
        val arr = value as? JsonValue.Arr
        if (arr == null) error(path, "expected an array, found ${describe(value)}")
        return arr
    }

    private fun stringAt(value: JsonValue?, path: String): String? {
        if (value == null) {
            error(path, "missing required string")
            return null
        }
        val str = value as? JsonValue.Str
        if (str == null) error(path, "expected a string, found ${describe(value)}")
        return str?.value
    }

    private fun stringListAt(value: JsonValue, path: String): List<String>? {
        val arr = arrAt(value, path) ?: return null
        return arr.items.mapIndexedNotNull { index, item -> stringAt(item, "$path[$index]") }
    }

    private fun intAt(value: JsonValue?, path: String): Int? {
        if (value == null) {
            error(path, "missing required number")
            return null
        }
        val num = value as? JsonValue.Num
        if (num == null) {
            error(path, "expected a number, found ${describe(value)}")
            return null
        }
        if (num.value % 1.0 != 0.0 || num.value < Int.MIN_VALUE || num.value > Int.MAX_VALUE) {
            error(path, "expected an integer, got ${num.value}")
            return null
        }
        return num.value.toInt()
    }

    private fun JsonValue.Obj.str(key: String, path: String, required: Boolean = true): String? {
        val node = this[key]
        if (node == null) {
            if (required) error(child(path, key), "missing required string")
            return null
        }
        return stringAt(node, child(path, key))
    }

    private fun JsonValue.Obj.int(key: String, path: String, required: Boolean = true): Int? {
        val node = this[key]
        if (node == null) {
            if (required) error(child(path, key), "missing required number")
            return null
        }
        return intAt(node, child(path, key))
    }

    private fun JsonValue.Obj.bool(key: String, path: String, required: Boolean = true): Boolean? {
        val node = this[key]
        if (node == null) {
            if (required) error(child(path, key), "missing required boolean")
            return null
        }
        val bool = node as? JsonValue.Bool
        if (bool == null) error(child(path, key), "expected a boolean, found ${describe(node)}")
        return bool?.value
    }

    private fun JsonValue.Obj.obj(key: String, path: String, required: Boolean = true): JsonValue.Obj? {
        val node = this[key]
        if (node == null) {
            if (required) error(child(path, key), "missing required object")
            return null
        }
        return objAt(node, child(path, key))
    }

    private fun JsonValue.Obj.arr(key: String, path: String, required: Boolean = true): JsonValue.Arr? {
        val node = this[key]
        if (node == null) {
            if (required) error(child(path, key), "missing required array")
            return null
        }
        return arrAt(node, child(path, key))
    }

    private fun JsonValue.Obj.strings(key: String, path: String, required: Boolean = true): List<String>? {
        val node = this[key]
        if (node == null) {
            if (required) error(child(path, key), "missing required array")
            return null
        }
        return stringListAt(node, child(path, key))
    }
}
