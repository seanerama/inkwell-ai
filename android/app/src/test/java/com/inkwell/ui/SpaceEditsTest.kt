package com.inkwell.ui

import com.inkwell.data.SpaceEntity
import com.inkwell.net.ApiException
import com.inkwell.net.ErrorBody
import com.inkwell.net.SpacePatchRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure Stage-15 (space-editing) core [SpaceEdits]: the change-diff
 * (only changed fields → a partial [SpacePatchRequest]), the position-swap (two PATCHes), and
 * the error mapping (403/409/422/network → the user string). No `android.graphics` is touched
 * (the CI-failure guard) — these run on the plain JVM.
 */
class SpaceEditsTest {

    private fun space(
        id: String = "s1",
        name: String = "Work",
        color: String = "#111111",
        model: String = "claude-sonnet-5",
        systemPrompt: String = "be terse",
        position: Int = 0,
    ) = SpaceEntity(
        id = id, name = name, slug = "work", systemPrompt = systemPrompt, tools = emptyList(),
        model = model, color = color, position = position, createdAt = 0L,
    )

    @Test
    fun diff_carries_only_the_changed_fields() {
        val original = space()
        val draft = SpaceEdits.draftOf(original).copy(systemPrompt = "answer in French")

        val patch = SpaceEdits.diff(original, draft)

        assertEquals("answer in French", patch.systemPrompt)
        assertNull("name unchanged → omitted", patch.name)
        assertNull("color unchanged → omitted", patch.color)
        assertNull("model unchanged → omitted", patch.model)
        assertNull("position never set by diff", patch.position)
    }

    @Test
    fun diff_of_an_unchanged_draft_is_empty_and_hasChanges_is_false() {
        val original = space()
        val draft = SpaceEdits.draftOf(original)

        assertEquals(SpacePatchRequest(), SpaceEdits.diff(original, draft))
        assertFalse(SpaceEdits.hasChanges(original, draft))
    }

    @Test
    fun a_changed_field_flips_hasChanges_true() {
        val original = space()
        val draft = SpaceEdits.draftOf(original).copy(name = "Learning")

        assertTrue(SpaceEdits.hasChanges(original, draft))
        assertEquals("Learning", SpaceEdits.diff(original, draft).name)
    }

    @Test
    fun name_is_trimmed_and_a_whitespace_only_change_is_no_change() {
        val original = space(name = "Work")
        // Same name with surrounding whitespace → trimmed → equal → omitted.
        val unchanged = SpaceEdits.draftOf(original).copy(name = "  Work  ")
        assertNull(SpaceEdits.diff(original, unchanged).name)
        assertFalse(SpaceEdits.hasChanges(original, unchanged))

        // A real rename is trimmed before it goes on the wire.
        val renamed = SpaceEdits.draftOf(original).copy(name = "  Home  ")
        assertEquals("Home", SpaceEdits.diff(original, renamed).name)
    }

    @Test
    fun multiple_changed_fields_are_all_present() {
        val original = space()
        val draft = SpaceEdits.draftOf(original).copy(color = "#2F6FED", model = "claude-opus-5")

        val patch = SpaceEdits.diff(original, draft)

        assertEquals("#2F6FED", patch.color)
        assertEquals("claude-opus-5", patch.model)
        assertNull(patch.name)
        assertNull(patch.systemPrompt)
    }

    @Test
    fun swap_produces_the_two_position_patches() {
        val a = space(id = "a", position = 1)
        val b = space(id = "b", position = 2)

        val patches = SpaceEdits.swap(a, b)

        assertEquals(2, patches.size)
        assertEquals("a" to SpacePatchRequest(position = 2), patches[0])
        assertEquals("b" to SpacePatchRequest(position = 1), patches[1])
        // Only `position` is set on either patch.
        assertNull(patches[0].second.name)
        assertNull(patches[1].second.color)
    }

    @Test
    fun error_mapping_covers_403_409_422_and_network() {
        assertEquals(
            "Editing spaces is turned off on the server",
            SpaceEdits.errorMessage(ApiException(403, ErrorBody("disabled", "editing disabled"))),
        )
        assertEquals(
            "A space with that name already exists",
            SpaceEdits.errorMessage(ApiException(409, ErrorBody("conflict", "slug exists"))),
        )
        assertEquals(
            "name must be 1-120 characters",
            SpaceEdits.errorMessage(ApiException(422, ErrorBody("validation", "name must be 1-120 characters"))),
        )
        // Network / unpaired / any non-ApiException → the offline copy, sheet kept open.
        assertEquals(SpaceEdits.OFFLINE, SpaceEdits.errorMessage(java.io.IOException("no route")))
    }

    @Test
    fun a_403_by_code_alone_still_maps_to_disabled() {
        assertEquals(
            "Editing spaces is turned off on the server",
            SpaceEdits.errorMessage(ApiException(400, ErrorBody("disabled", "x"))),
        )
    }
}
