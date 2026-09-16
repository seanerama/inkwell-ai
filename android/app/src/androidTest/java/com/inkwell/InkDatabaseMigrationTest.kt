package com.inkwell

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inkwell.data.InkDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Stage 10 Room v1 → v2 migration test (emulator lane). Asserts the migration is
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
}
