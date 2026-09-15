package com.inkwell.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.inkwell.data.dao.CanvasDao
import com.inkwell.data.dao.LayerDao
import com.inkwell.data.dao.RasterDao
import com.inkwell.data.dao.SpaceDao
import com.inkwell.data.dao.StrokeDao

/**
 * The device-local ink database (contract `ink-storage`). Version 1 is the first,
 * frozen schema; the schema JSON is exported to android/app/schemas/ and committed.
 *
 * A destructive fallback is forbidden in release builds (contract): [create] never
 * calls fallbackToDestructiveMigration, so a schema mismatch fails loudly rather
 * than silently wiping the user's ink. Later schema changes ship explicit Migrations.
 */
@Database(
    entities = [
        SpaceEntity::class,
        CanvasEntity::class,
        LayerEntity::class,
        StrokeEntity::class,
        RasterEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class InkDatabase : RoomDatabase() {
    abstract fun spaceDao(): SpaceDao
    abstract fun canvasDao(): CanvasDao
    abstract fun layerDao(): LayerDao
    abstract fun strokeDao(): StrokeDao
    abstract fun rasterDao(): RasterDao

    companion object {
        const val NAME = "inkwell.db"

        fun create(context: Context): InkDatabase =
            Room.databaseBuilder(context, InkDatabase::class.java, NAME)
                // No fallbackToDestructiveMigration (contract ink-storage).
                .build()
    }
}
