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
 * destructively, so ink survives. The exported schema JSON lives in android/app/schemas/
 * and is committed.
 *
 * A destructive fallback is forbidden in release builds (contract): [create] never calls
 * fallbackToDestructiveMigration, so a schema mismatch fails loudly rather than silently
 * wiping the user's ink. Later schema changes ship explicit Migrations.
 */
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
    version = 3,
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

        fun create(context: Context): InkDatabase =
            Room.databaseBuilder(context, InkDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // No fallbackToDestructiveMigration (contract ink-storage).
                .build()
    }
}
