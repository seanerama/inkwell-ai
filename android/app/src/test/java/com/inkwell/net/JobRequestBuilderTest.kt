package com.inkwell.net

import com.inkwell.render.CoordinateMapping
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * JVM unit test for [JobRequestBuilder] against contract `device-api` §Schema/wire:
 * the `POST /jobs` body serializes with the contract's exact field names, `export`
 * carries `{ w, h, width_cu, height_cu }`, and `image` is valid base64 of a PNG.
 */
class JobRequestBuilderTest {

    // Uses explicitNulls=false like the production ApiClient, so optional nulls are omitted.
    private val json = Json { explicitNulls = false }

    /** Minimal but valid PNG: the 8-byte signature followed by an (empty) IEND-ish tail. */
    private val pngBytes: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, // fake IEND marker bytes
    )

    private fun buildDefault() = JobRequestBuilder.build(
        type = "canvas.annotate",
        spaceId = "space-uuid",
        canvasId = "canvas-uuid",
        pngBytes = pngBytes,
        export = CoordinateMapping.export(), // 1109×1568 for the default canvas
        instruction = "mark up the dependencies",
        selection = listOf(0.1, 0.2, 0.5, 0.3),
    )

    @Test
    fun serializes_with_contract_field_names() {
        val body = json.encodeToString(JobCreateRequest.serializer(), buildDefault())
        val obj = json.parseToJsonElement(body).jsonObject

        assertEquals("canvas.annotate", obj["type"]!!.jsonPrimitive.content)
        assertEquals("space-uuid", obj["space_id"]!!.jsonPrimitive.content)
        assertEquals("canvas-uuid", obj["canvas_id"]!!.jsonPrimitive.content)
        assertTrue("image present", obj.containsKey("image"))
        assertEquals("mark up the dependencies", obj["instruction"]!!.jsonPrimitive.content)

        // export sub-object uses the exact snake_case keys.
        val export = obj["export"]!!.jsonObject
        assertEquals(1109, export["w"]!!.jsonPrimitive.content.toInt())
        assertEquals(1568, export["h"]!!.jsonPrimitive.content.toInt())
        assertEquals(2480, export["width_cu"]!!.jsonPrimitive.content.toInt())
        assertEquals(3508, export["height_cu"]!!.jsonPrimitive.content.toInt())

        // selection is a normalized [x,y,w,h] array.
        val selection = obj["selection"] as kotlinx.serialization.json.JsonArray
        assertEquals(4, selection.size)
        assertEquals(0.1, selection[0].jsonPrimitive.content.toDouble(), 1e-9)
    }

    @Test
    fun image_is_valid_base64_of_a_png() {
        val req = buildDefault()
        val decoded = Base64.getDecoder().decode(req.image!!)
        // Round-trips to the original bytes...
        assertArrayEquals(pngBytes, decoded)
        // ...and begins with the PNG magic signature.
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertArrayEquals(sig, decoded.copyOfRange(0, 8))
    }

    @Test
    fun optional_fields_omitted_when_null() {
        val req = JobRequestBuilder.build(
            type = "canvas.ask",
            spaceId = "s",
            canvasId = "c",
            pngBytes = pngBytes,
            export = CoordinateMapping.export(),
            instruction = null,
            selection = null,
        )
        val obj = json.parseToJsonElement(json.encodeToString(JobCreateRequest.serializer(), req)).jsonObject
        assertTrue("instruction omitted", !obj.containsKey("instruction"))
        assertTrue("selection omitted", !obj.containsKey("selection"))
        assertTrue("image still present", obj.containsKey("image"))
    }
}
