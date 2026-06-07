package com.orbitalfrontier.playthrough

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Encodes/decodes a [Playthrough] to and from a stable, diffable JSON text form (UC02 AC#3/#8).
 *
 * One shared [Json] configuration is the single source of truth for the on-disk shape:
 * - `prettyPrint` + a fixed indent ⇒ line-oriented, reviewable, git-diffable artifacts.
 * - `encodeDefaults` ⇒ every field is written explicitly, so a default-valued field is visible in
 *   the artifact and a later change to a default doesn't silently alter how old files decode.
 * - the `"type"` discriminator (kotlinx default) keeps the polymorphic [InputEvent] entries
 *   self-describing.
 *
 * Round-trips losslessly: `decode(encode(p)) == p`.
 */
object PlaythroughCodec {
    // prettyPrintIndent is a kotlinx ExperimentalSerializationApi knob; we opt in deliberately to
    // pin the artifact's two-space indent (stable/diffable form, UC02 AC#8).
    @OptIn(ExperimentalSerializationApi::class)
    private val json: Json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
        }

    /** Serialize [playthrough] to stable JSON text. */
    fun encode(playthrough: Playthrough): String = json.encodeToString(playthrough)

    /** Parse a [Playthrough] from JSON [text] produced by [encode]. */
    fun decode(text: String): Playthrough = json.decodeFromString(text)
}
