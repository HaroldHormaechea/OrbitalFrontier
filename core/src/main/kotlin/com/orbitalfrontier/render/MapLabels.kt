package com.orbitalfrontier.render

import com.orbitalfrontier.world.Contact
import com.orbitalfrontier.world.ContactKind
import com.orbitalfrontier.world.Named
import com.orbitalfrontier.world.Poi
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.Transponder

/**
 * Pure, libGDX-free policy for **which** map markers get a name label (UC24), sitting beside the other
 * pure layout objects ([MinimapLayout]/[MapOverlayLayout]) so the decision is asserted directly in a
 * JVM unit test (ADR 0001). The renderers own the *drawing*; this object owns the *predicate*.
 *
 * A label is shown for a POI only when all three hold:
 *  1. **Visible as a contact** — the POI is a [Contact] that the map is currently drawing: a
 *     [Transponder] (always shown) or a hidden contact whose id is in `revealedContacts` (UC10). This
 *     is the exact inverse of the renderers' marker-skip filter, so a label never appears without its
 *     marker.
 *  2. **Named, non-blank** — the POI is [Named] with a non-blank [Named.displayName] (UC24 AC#3: no
 *     empty/placeholder labels).
 *  3. **Anti-clutter per surface** (UC24 AC#4) — the small HUD minimap would clutter if every named
 *     POI were labelled, so it labels only stations (keyed off the [ContactKind] enum, not the
 *     concrete `Station` type, to stay on the Open/Closed seam); the roomy zoomed overlay labels every
 *     visible named contact.
 */
object MapLabels {
    /** Which map surface a label decision is for — drives the anti-clutter rule (UC24 AC#4). */
    enum class Surface { MINIMAP, OVERLAY }

    /**
     * Whether [poi] should be labelled on [surface] given the currently [revealedContacts]. See the
     * class doc for the three conditions; returns `false` unless all hold.
     */
    fun shouldLabel(
        poi: Poi,
        revealedContacts: Set<PoiId>,
        surface: Surface,
    ): Boolean {
        if (poi !is Contact) return false
        if (poi !is Transponder && poi.id !in revealedContacts) return false
        if (poi !is Named || poi.displayName.isBlank()) return false
        return when (surface) {
            Surface.OVERLAY -> true
            Surface.MINIMAP -> poi.contactKind == ContactKind.STATION
        }
    }
}
