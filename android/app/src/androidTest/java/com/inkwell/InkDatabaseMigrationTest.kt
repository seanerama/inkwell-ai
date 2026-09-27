package com.inkwell

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inkwell.data.InkDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Stage 10 Room v1 → v2 migration test (emulator lane). Stage 32 adds v4 → v5 (the
 * page-grid columns and the off-page ink repair, ADR-0014). Asserts the migration is
 * additive: a stroke written under schema v1 SURVIVES the upgrade to v2 (which only adds
 * the `card_states` table), and the new table exists and is writable. This is the
 * contract `ink-storage` "no destructive migration" guarantee.
 */
@RunWith(AndroidJUnit4::class)
class InkDatabaseMigrationTest {

    private val testDb = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        InkDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    @Throws(IOException::class)
    fun migrate1To2_keepsStrokes_andAddsCardStates() {
        // Create the v1 database and write one layer + one stroke.
        helper.createDatabase(testDb, 1).apply {
            execSQL(
                "INSERT INTO layers (id, canvas_id, z, owner, type, visible, opacity, job_id, created_at) " +
                    "VALUES ('layer-1', 'canvas-1', 0, 'user', 'ink', 1, 1.0, NULL, 1)",
            )
            execSQL(
                "INSERT INTO strokes (id, layer_id, tool, color, width_cu, points, point_count, " +
                    "bbox_x, bbox_y, bbox_w, bbox_h, created_at) " +
                    "VALUES ('stroke-1', 'layer-1', 'pen', '#111111', 3.0, x'00', 1, 0.0, 0.0, 1.0, 1.0, 1)",
            )
            close()
        }

        // Run the real migration and validate the DB matches the exported v2 schema.
        val db = helper.runMigrationsAndValidate(testDb, 2, true, InkDatabase.MIGRATION_1_2)

        // The stroke written under v1 is still there (additive migration, no data loss).
        db.query("SELECT COUNT(*) FROM strokes").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        // The new card_states table exists and is writable.
        db.execSQL(
            "INSERT INTO card_states (id, job_id, state, updated_at) " +
                "VALUES ('card-1', 'job-1', 'done', 2)",
        )
        db.query("SELECT state FROM card_states WHERE id = 'card-1'").use { c ->
            c.moveToFirst()
            assertEquals("done", c.getString(0))
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate2To3_keepsStrokes_andAddsFoldersAndCanvasColumns() {
        // Create the v2 database and write one layer, one stroke, and one canvas.
        val strokeBlob = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        helper.createDatabase(testDb, 2).apply {
            execSQL(
                "INSERT INTO canvases (id, space_id, title, width_cu, height_cu, created_at, updated_at, origin) " +
                    "VALUES ('canvas-1', 'space-1', 'Topology', 2480, 3508, 1, 1, 'user')",
            )
            execSQL(
                "INSERT INTO layers (id, canvas_id, z, owner, type, visible, opacity, job_id, created_at) " +
                    "VALUES ('layer-1', 'canvas-1', 0, 'user', 'ink', 1, 1.0, NULL, 1)",
            )
            val cv = android.content.ContentValues().apply {
                put("id", "stroke-1")
                put("layer_id", "layer-1")
                put("tool", "pen")
                put("color", "#111111")
                put("width_cu", 3.0)
                put("points", strokeBlob)
                put("point_count", 1)
                put("bbox_x", 0.0)
                put("bbox_y", 0.0)
                put("bbox_w", 1.0)
                put("bbox_h", 1.0)
                put("created_at", 1L)
            }
            insert("strokes", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, cv)
            close()
        }

        // Run the real v2 → v3 migration and validate against the exported v3 schema.
        val db = helper.runMigrationsAndValidate(testDb, 3, true, InkDatabase.MIGRATION_2_3)

        // The stroke written under v2 survives byte-for-byte (additive migration, no loss).
        db.query("SELECT points FROM strokes WHERE id = 'stroke-1'").use { c ->
            c.moveToFirst()
            assertArrayEquals(strokeBlob, c.getBlob(0))
        }
        // The existing canvas is still there and stays at the root (folder_id NULL, undeleted).
        db.query("SELECT folder_id, deleted_at FROM canvases WHERE id = 'canvas-1'").use { c ->
            c.moveToFirst()
            assertEquals(true, c.isNull(0))
            assertEquals(true, c.isNull(1))
        }
        // The new folders table exists and is writable.
        db.execSQL(
            "INSERT INTO folders (id, space_id, parent_id, name, created_at, updated_at, deleted_at) " +
                "VALUES ('folder-1', 'space-1', NULL, 'Network', 2, 2, NULL)",
        )
        db.query("SELECT name FROM folders WHERE id = 'folder-1'").use { c ->
            c.moveToFirst()
            assertEquals("Network", c.getString(0))
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4_keepsInk_andAddsSeenAt_withRastersUntouched() {
        // Create the v3 database with a canvas + layer + stroke, plus a v1 `rasters` row.
        val strokeBlob = byteArrayOf(0x05, 0x06, 0x07, 0x08)
        helper.createDatabase(testDb, 3).apply {
            execSQL(
                "INSERT INTO canvases (id, space_id, title, width_cu, height_cu, created_at, updated_at, origin, folder_id, deleted_at) " +
                    "VALUES ('canvas-1', 'space-1', 'Brief', 2480, 3508, 1, 1, 'user', NULL, NULL)",
            )
            execSQL(
                "INSERT INTO layers (id, canvas_id, z, owner, type, visible, opacity, job_id, created_at) " +
                    "VALUES ('layer-1', 'canvas-1', 0, 'user', 'ink', 1, 1.0, NULL, 1)",
            )
            val cv = android.content.ContentValues().apply {
                put("id", "stroke-1")
                put("layer_id", "layer-1")
                put("tool", "pen")
                put("color", "#111111")
                put("width_cu", 3.0)
                put("points", strokeBlob)
                put("point_count", 1)
                put("bbox_x", 0.0)
                put("bbox_y", 0.0)
                put("bbox_w", 1.0)
                put("bbox_h", 1.0)
                put("created_at", 1L)
            }
            insert("strokes", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, cv)
            // A raster row (the v1 `rasters` table must be untouched by v3→v4).
            execSQL(
                "INSERT INTO rasters (id, layer_id, blob_uri, mime, page, x_cu, y_cu, w_cu, h_cu) " +
                    "VALUES ('raster-1', 'layer-1', '/cache/push/raster-1.pdf', 'application/pdf', 0, 0.0, 0.0, 2480.0, 3508.0)",
            )
            close()
        }

        // Run the real v3 → v4 migration and validate against the exported v4 schema.
        val db = helper.runMigrationsAndValidate(testDb, 4, true, InkDatabase.MIGRATION_3_4)

        // The stroke written under v3 survives byte-for-byte (additive migration, no loss).
        db.query("SELECT points FROM strokes WHERE id = 'stroke-1'").use { c ->
            c.moveToFirst()
            assertArrayEquals(strokeBlob, c.getBlob(0))
        }
        // The new seen_at column exists and is NULL for the pre-existing canvas.
        db.query("SELECT seen_at FROM canvases WHERE id = 'canvas-1'").use { c ->
            c.moveToFirst()
            assertEquals(true, c.isNull(0))
        }
        // The v1 rasters table is unchanged — its row (and its blob_uri) still reads back.
        db.query("SELECT blob_uri FROM rasters WHERE id = 'raster-1'").use { c ->
            c.moveToFirst()
            assertEquals("/cache/push/raster-1.pdf", c.getString(0))
        }
        // seen_at is writable (a canvas can be marked seen).
        db.execSQL("UPDATE canvases SET seen_at = 99 WHERE id = 'canvas-1'")
        db.query("SELECT seen_at FROM canvases WHERE id = 'canvas-1'").use { c ->
            c.moveToFirst()
            assertEquals(99, c.getInt(0))
        }
        db.close()
    }

    // --- Stage 32: v4 → v5 (page grid, ADR-0014) ---

    /** Insert a v4 canvas row (page size [w]×[h]). */
    private fun androidx.sqlite.db.SupportSQLiteDatabase.canvasV4(id: String, w: Int = 2480, h: Int = 3508) {
        execSQL(
            "INSERT INTO canvases (id, space_id, title, width_cu, height_cu, created_at, updated_at, origin, folder_id, deleted_at, seen_at) " +
                "VALUES ('$id', 'space-1', '$id', $w, $h, 1, 1, 'user', NULL, NULL, NULL)",
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.layer(id: String, canvasId: String, z: Int = 0) {
        execSQL(
            "INSERT INTO layers (id, canvas_id, z, owner, type, visible, opacity, job_id, created_at) " +
                "VALUES ('$id', '$canvasId', $z, 'user', 'ink', 1, 1.0, NULL, 1)",
        )
    }

    /** A stroke whose bbox is [x, y, w, h]; its points blob is a recognisable byte pattern. */
    private fun androidx.sqlite.db.SupportSQLiteDatabase.strokeBbox(id: String, layerId: String, x: Double, y: Double, w: Double, h: Double) {
        val cv = android.content.ContentValues().apply {
            put("id", id)
            put("layer_id", layerId)
            put("tool", "pen")
            put("color", "#111111")
            put("width_cu", 3.0)
            put("points", blobFor(id))
            put("point_count", 1)
            put("bbox_x", x)
            put("bbox_y", y)
            put("bbox_w", w)
            put("bbox_h", h)
            put("created_at", 1L)
        }
        insert("strokes", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, cv)
    }

    private fun blobFor(id: String): ByteArray = id.toByteArray() + byteArrayOf(0x7F, 0x00, -0x01)

    private fun androidx.sqlite.db.SupportSQLiteDatabase.grid(canvasId: String): List<Int> =
        query("SELECT page_min_col, page_max_col, page_min_row, page_max_row FROM canvases WHERE id = '$canvasId'").use { c ->
            c.moveToFirst()
            listOf(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3))
        }

    @Test
    @Throws(IOException::class)
    fun migrate4To5_addsGridDefaults_andKeepsInk() {
        helper.createDatabase(testDb, 4).apply {
            canvasV4("inside")
            layer("l-inside", "inside")
            strokeBbox("s-inside", "l-inside", 10.0, 20.0, 2400.0, 3400.0)
            canvasV4("empty")
            close()
        }
        val db = helper.runMigrationsAndValidate(testDb, 5, true, InkDatabase.MIGRATION_4_5)

        // Existing canvases open unchanged: exactly page (0,0).
        assertEquals(listOf(0, 0, 0, 0), db.grid("inside"))
        assertEquals(listOf(0, 0, 0, 0), db.grid("empty"))
        db.query("SELECT points, bbox_x, bbox_w FROM strokes WHERE id = 's-inside'").use { c ->
            c.moveToFirst()
            assertArrayEquals(blobFor("s-inside"), c.getBlob(0))
            assertEquals(10.0, c.getDouble(1), 0.0)
            assertEquals(2400.0, c.getDouble(2), 0.0)
        }
        // A canvas inserted after the migration without the columns gets the defaults.
        db.execSQL(
            "INSERT INTO canvases (id, space_id, title, width_cu, height_cu, created_at, updated_at, origin) " +
                "VALUES ('new', 'space-1', 'New', 2480, 3508, 2, 2, 'user')",
        )
        assertEquals(listOf(0, 0, 0, 0), db.grid("new"))
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate4To5_repairsOffPageInk_positiveNegativeAndClamped_withoutTouchingPoints() {
        val w = 2480.0
        val h = 3508.0
        helper.createDatabase(testDb, 4).apply {
            // Ink written past the right and bottom edges (the v0.0.20 report).
            canvasV4("right-down")
            layer("l-rd", "right-down")
            strokeBbox("s-rd-1", "l-rd", 100.0, 100.0, 200.0, 200.0)
            strokeBbox("s-rd-2", "l-rd", 2300.0, 3400.0, 400.0, 300.0) // to x=2700, y=3700
            // Ink above and to the left (negative coordinates), over two layers.
            canvasV4("left-up")
            layer("l-lu-a", "left-up", 0)
            layer("l-lu-b", "left-up", 1)
            strokeBbox("s-lu-1", "l-lu-a", -50.0, 500.0, 100.0, 10.0)
            strokeBbox("s-lu-2", "l-lu-b", 300.0, -h - 20.0, 10.0, 40.0) // row -2
            // Ink far to the right: clamped to 8 pages, page 0 kept.
            canvasV4("far")
            layer("l-far", "far")
            strokeBbox("s-far", "l-far", 30 * w, 10.0, 5.0, 5.0)
            // A non-A4 page size uses its own page size.
            canvasV4("small", w = 1000, h = 1000)
            layer("l-small", "small")
            strokeBbox("s-small", "l-small", 1500.0, 10.0, 10.0, 10.0)
            // Ink exactly on page (0,0): untouched.
            canvasV4("on-page")
            layer("l-on", "on-page")
            strokeBbox("s-on", "l-on", 0.0, 0.0, w - 1, h - 1)
            close()
        }
        val db = helper.runMigrationsAndValidate(testDb, 5, true, InkDatabase.MIGRATION_4_5)

        assertEquals(listOf(0, 1, 0, 1), db.grid("right-down"))
        assertEquals(listOf(-1, 0, -2, 0), db.grid("left-up"))
        assertEquals(listOf(0, 7, 0, 0), db.grid("far"))
        assertEquals(listOf(0, 1, 0, 0), db.grid("small"))
        assertEquals(listOf(0, 0, 0, 0), db.grid("on-page"))

        // Stored points and bboxes are never modified.
        db.query("SELECT id, points, bbox_x, bbox_y FROM strokes ORDER BY id").use { c ->
            var n = 0
            while (c.moveToNext()) {
                val id = c.getString(0)
                assertArrayEquals("points of $id", blobFor(id), c.getBlob(1))
                n++
            }
            assertEquals(7, n)
        }
        db.query("SELECT bbox_x, bbox_y FROM strokes WHERE id = 's-lu-2'").use { c ->
            c.moveToFirst()
            assertEquals(300.0, c.getDouble(0), 0.0)
            assertEquals(-h - 20.0, c.getDouble(1), 0.0)
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrateAll1To5_keepsInk() {
        helper.createDatabase(testDb, 1).apply {
            execSQL(
                "INSERT INTO canvases (id, space_id, title, width_cu, height_cu, created_at, updated_at, origin) " +
                    "VALUES ('c1', 'space-1', 'Old', 2480, 3508, 1, 1, 'user')",
            )
            layer("l1", "c1")
            strokeBbox("s1", "l1", 2500.0, 10.0, 10.0, 10.0)
            close()
        }
        val db = helper.runMigrationsAndValidate(
            testDb, 5, true,
            InkDatabase.MIGRATION_1_2, InkDatabase.MIGRATION_2_3, InkDatabase.MIGRATION_3_4, InkDatabase.MIGRATION_4_5,
        )
        assertEquals(listOf(0, 1, 0, 0), db.grid("c1"))
        db.query("SELECT points FROM strokes WHERE id = 's1'").use { c ->
            c.moveToFirst()
            assertArrayEquals(blobFor("s1"), c.getBlob(0))
        }
        db.close()
    }
}
