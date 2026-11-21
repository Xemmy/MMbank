package com.xemmy.mmbank.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureCategoryResolverTest {

    @Test
    fun `a confirmed mapping wins outright, even over a bridge match`() {
        val result = CaptureCategoryResolver.resolve(
            confirmedCategoryId = 1L,
            hasPendingSuggestion = false,
            bridgedCategoryId = 2L,
        )
        assertEquals(1L, result)
    }

    @Test
    fun `a pending suggestion blocks the bridge, leaving categoryId unset for review`() {
        val result = CaptureCategoryResolver.resolve(
            confirmedCategoryId = null,
            hasPendingSuggestion = true,
            bridgedCategoryId = 2L,
        )
        assertNull(result)
    }

    @Test
    fun `the bridge applies when neither a confirmed match nor a pending suggestion exists`() {
        val result = CaptureCategoryResolver.resolve(
            confirmedCategoryId = null,
            hasPendingSuggestion = false,
            bridgedCategoryId = 2L,
        )
        assertEquals(2L, result)
    }

    @Test
    fun `nothing resolves when there is no signal at all`() {
        val result = CaptureCategoryResolver.resolve(
            confirmedCategoryId = null,
            hasPendingSuggestion = false,
            bridgedCategoryId = null,
        )
        assertNull(result)
    }

    @Test
    fun `a pending suggestion with no bridge match still resolves to null`() {
        val result = CaptureCategoryResolver.resolve(
            confirmedCategoryId = null,
            hasPendingSuggestion = true,
            bridgedCategoryId = null,
        )
        assertNull(result)
    }
}
