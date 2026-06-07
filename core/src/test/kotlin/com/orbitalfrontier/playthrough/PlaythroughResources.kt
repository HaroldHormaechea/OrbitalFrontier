package com.orbitalfrontier.playthrough

/**
 * Loads a named [Playthrough] from the classpath (UC02 AC#7/#8).
 *
 * Artifacts live under the `playthroughs/` resource root (e.g. `playthroughs/<name>.json`), so a
 * test can `load("uc01-thrust-north")` and the file resolves from `src/test/resources/playthroughs/`
 * (or any other classpath root that ships one). Keeping the location convention in one place keeps
 * artifacts in a single, defined, diffable spot.
 */
object PlaythroughResources {
    /** Resource directory (classpath root–relative) that holds playthrough JSON files. */
    const val RESOURCE_DIR: String = "playthroughs"

    /** Classpath path of the named playthrough, e.g. `playthroughs/<name>.json`. */
    fun resourcePath(name: String): String = "$RESOURCE_DIR/$name.json"

    /**
     * Load and decode the playthrough named [name] from the classpath.
     *
     * @param classLoader the loader to resolve the resource against; defaults to this class's loader,
     *   which sees the test classpath when called from a JVM test.
     * @throws IllegalArgumentException if no such resource exists (a setup/wiring error — fail fast).
     */
    fun load(
        name: String,
        classLoader: ClassLoader = PlaythroughResources::class.java.classLoader,
    ): Playthrough {
        val path = resourcePath(name)
        val stream =
            classLoader.getResourceAsStream(path)
                ?: throw IllegalArgumentException("Playthrough resource not found on classpath: $path")
        val text = stream.bufferedReader().use { it.readText() }
        return PlaythroughCodec.decode(text)
    }
}
