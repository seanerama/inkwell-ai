package com.inkwell.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.inkwell.data.dao.CanvasDao
import com.inkwell.data.dao.CardStateDao
import com.inkwell.data.dao.FolderDao
import com.inkwell.data.dao.LayerDao
import com.inkwell.data.dao.RasterDao
import com.inkwell.data.dao.SpaceDao
import com.inkwell.data.dao.StrokeDao

/**
 * The device-local ink database (contract `ink-storage`).
 *
 * Version 1 is the first, frozen schema. Version 2 (Stage 10) is an **additive** change:
 * it adds the `card_states` table for per-card device state and leaves every v1 table —
 * ink strokes included — untouched, via [MIGRATION_1_2]. Version 3 (Stage 11) is also
 * **additive**: it adds the `folders` table and two nullable columns on `canvases`
 * (`folder_id`, `deleted_at`) via [MIGRATION_2_3] — no v1/v2 table is altered
 * destructively, so ink survives. Version 4 (Stage 22) is also **additive**: it adds one
 * nullable column `canvases.seen_at` (the unread marker for a pushed canvas) via
 * [MIGRATION_3_4] — no v1/v2/v3 table is altered destructively, so ink survives. Version 5
 * (Stage 32, ADR-0014) is also **additive**: four NOT NULL DEFAULT 0 page-grid columns on
 * `canvases` via [MIGRATION_4_5], which then grows the grid of any canvas whose stored
 * strokes lie off page (0,0) so that ink is visible again (stored points are never
 * touched). The exported schema JSON lives in android/app/schemas/ and is committed.
 *
 * A destructive fallback is forbidden in release builds (contract): [create] never calls
 * fallbackToDestructiveMigration, so a schema mismatch fails loudly rather than silently
 * wiping the user's ink. Later schema changes ship explicit Migrations.
 */
/**
 * The current Room schema version (stage 32: v5, the page grid). The single source for
 * `@Database(version = …)` and for tests that assert the version a fresh database opens at.
 */
const val INK_DB_VERSION = 5

@Database(
    entities = [
        SpaceEntity::class,
        CanvasEntity::class,
        LayerEntity::class,
        StrokeEntity::class,
        RasterEntity::class,
        CardStateEntity::class,
        FolderEntity::class,
    ],
    version = INK_DB_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class InkDatabase : RoomDatabase() {
    abstract fun spaceDao(): SpaceDao
    abstract fun canvasDao(): CanvasDao
    abstract fun layerDao(): LayerDao
    abstract fun strokeDao(): StrokeDao
    abstract fun rasterDao(): RasterDao
    abstract fun cardStateDao(): CardStateDao
    abstract fun folderDao(): FolderDao

    companion object {
        const val NAME = "inkwell.db"

        /**
         * v1 → v2 (Stage 10, additive): create `card_states`. Purely additive — no v1
         * table is altered, so existing ink and layers survive the upgrade unchanged.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `card_states` (" +
                        "`id` TEXT NOT NULL, " +
                        "`job_id` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_card_states_job_id` " +
                        "ON `card_states` (`job_id`)"
                )
            }
        }

        /**
         * v2 → v3 (Stage 11, additive): create the `folders` table with its two indices
         * and add the two nullable columns to `canvases` (`folder_id`, `deleted_at`).
         * Purely additive — no v1/v2 table is altered destructively, existing canvases
         * keep folder_id/deleted_at NULL (space root, undeleted), so ink survives.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folders` (" +
                        "`id` TEXT NOT NULL, " +
                        "`space_id` TEXT NOT NULL, " +
                        "`parent_id` TEXT, " +
                        "`name` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "`deleted_at` INTEGER, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_folders_space_id` " +
                        "ON `folders` (`space_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_folders_parent_id` " +
                        "ON `folders` (`parent_id`)",
                )
                db.execSQL("ALTER TABLE `canvases` ADD COLUMN `folder_id` TEXT")
                db.execSQL("ALTER TABLE `canvases` ADD COLUMN `deleted_at` INTEGER")
            }
        }

        /**
         * v3 → v4 (Stage 22, additive): add the nullable `seen_at` column to `canvases`
         * (the unread marker for a pushed, agent-origin canvas). Purely additive — no
         * v1/v2/v3 table is altered, `rasters` (a v1 table) is untouched, and existing
         * canvases keep `seen_at` NULL, so ink survives the upgrade unchanged.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `canvases` ADD COLUMN `seen_at` INTEGER")
            }
        }

        /**
         * v4 → v5 (Stage 32, ADR-0014, additive): add the page-grid extent to `canvases`
         * (`page_min_col`, `page_max_col`, `page_min_row`, `page_max_row`, INTEGER NOT NULL
         * DEFAULT 0) — every existing canvas becomes exactly page (0,0) — then run the
         * off-page ink repair ([repairPageGrids]) inside the same migration transaction.
         * No table is altered destructively and no stroke row is written.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (col in PAGE_GRID_COLUMNS) {
                    db.execSQL("ALTER TABLE `canvases` ADD COLUMN `$col` INTEGER NOT NULL DEFAULT 0")
                }
                repairPageGrids(db)
            }
        }

        /** The four v5 page-grid columns on `canvases` (contract `ink-storage` ADR-0014). */
        val PAGE_GRID_COLUMNS = listOf("page_min_col", "page_max_col", "page_min_row", "page_max_row")

        /**
         * The v5 repair (contract `ink-storage`: "the v5 migration grows the grid of any
         * canvas whose existing strokes lie off page (0,0) so that it covers their bboxes,
         * capped; it never moves or edits stored points"). Since v0.0.20, ink written off
         * the page was stored but never shown; this makes it visible again.
         *
         * For each canvas, the union of its strokes' `bbox_*` columns (every layer) is read
         * with one aggregate query; [PageExtent.covering] turns it into the smallest
         * whole-page grid containing page (0,0) and the ink, clamped to 8 pages per axis.
         * Only canvases whose grid differs from (0,0,0,0) are updated. `strokes` is only
         * read. Returns the number of canvases whose grid was grown (for tests / logs).
         */
        fun repairPageGrids(db: SupportSQLiteDatabase): Int {
            val grown = ArrayList<Pair<String, PageExtent>>()
            db.query(
                "SELECT c.id, c.width_cu, c.height_cu, MIN(s.bbox_x), MIN(s.bbox_y), " +
                    "MAX(s.bbox_x + s.bbox_w), MAX(s.bbox_y + s.bbox_h) " +
                    "FROM canvases c JOIN layers l ON l.canvas_id = c.id " +
                    "JOIN strokes s ON s.layer_id = l.id GROUP BY c.id",
            ).use { c ->
                while (c.moveToNext()) {
                    if ((3..6).any { c.isNull(it) }) continue
                    val extent = PageExtent.covering(
                        minX = c.getDouble(3),
                        minY = c.getDouble(4),
                        maxX = c.getDouble(5),
                        maxY = c.getDouble(6),
                        pageW = c.getInt(1),
                        pageH = c.getInt(2),
                    )
                    if (extent != PageExtent.SINGLE) grown.add(c.getString(0) to extent)
                }
            }
            for ((id, e) in grown) {
                db.execSQL(
                    "UPDATE `canvases` SET `page_min_col` = ?, `page_max_col` = ?, " +
                        "`page_min_row` = ?, `page_max_row` = ? WHERE `id` = ?",
                    arrayOf<Any?>(e.minCol, e.maxCol, e.minRow, e.maxRow, id),
                )
            }
            return grown.size
        }

        fun create(context: Context): InkDatabase =
            Room.databaseBuilder(context, InkDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // No fallbackToDestructiveMigration (contract ink-storage).
                .build()
    }
}
