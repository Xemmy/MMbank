package com.xemmy.mmbank.domain.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLayoutTest {
    private val specs = listOf(
        SectionSpec("a", "A"), SectionSpec("b", "B"), SectionSpec("c", "C", removable = false), SectionSpec("d", "D"),
    )

    @Test fun roundTrip() {
        val l = ScreenLayout(listOf("d", "a", "c", "b"), setOf("a"))
        assertEquals(l, ScreenLayoutCodec.decode(ScreenLayoutCodec.encode(l), specs))
    }

    @Test fun blankAndGarbageFallBackToDefaults() {
        val def = ScreenLayout.default(specs)
        assertEquals(def, ScreenLayoutCodec.decode("", specs))
        assertEquals(def, ScreenLayoutCodec.decode(null, specs))
        assertEquals(def, ScreenLayoutCodec.decode("???", specs))
    }

    @Test fun unknownAndDuplicateIdsDropped() {
        val l = ScreenLayoutCodec.decode("b,zzz,b,a,c,d|nope", specs)
        assertEquals(listOf("b", "a", "c", "d"), l.order)
        assertTrue(l.hidden.isEmpty())
    }

    @Test fun newSectionInsertedAfterPredecessor() {
        val l = ScreenLayoutCodec.decode("d,a,b|", specs) // "c" is new
        assertEquals(listOf("d", "a", "b", "c").let { it }, l.order.filter { it != "c" } + "c")
        assertEquals(l.order.indexOf("b") + 1, l.order.indexOf("c"))
    }

    @Test fun newFirstSectionGoesToFront() {
        val l = ScreenLayoutCodec.decode("b,c,d|", specs)
        assertEquals(listOf("a", "b", "c", "d"), l.order)
    }

    @Test fun nonRemovableNeverHidden() {
        assertTrue(ScreenLayoutCodec.decode("a,b,c,d|c,a", specs).hidden == setOf("a"))
        val l = ScreenLayout.default(specs)
        assertEquals(l, l.toggle("c", specs))
        assertEquals(setOf("a"), l.toggle("a", specs).hidden)
        assertTrue(l.toggle("a", specs).toggle("a", specs).hidden.isEmpty())
        assertEquals(l, l.toggle("unknown", specs))
    }

    @Test fun moveAndVisibleOrder() {
        val l = ScreenLayout.default(specs)
        assertEquals(l, l.move("a", -1))
        assertEquals(l, l.move("d", 1))
        assertEquals(listOf("b", "a", "c", "d"), l.move("a", 1).order)
        assertEquals(listOf("a", "c", "d"), l.toggle("b", specs).visibleOrder)
    }

    @Test fun defaultIdsUnique() {
        assertEquals(HomeSections.specs.size, HomeSections.specs.map { it.id }.toSet().size)
    }

    @Test fun insightsDefaultIdsUnique() {
        assertEquals(InsightsSections.specs.size, InsightsSections.specs.map { it.id }.toSet().size)
        assertTrue(InsightsSections.specs.all { it.removable })
    }
}
