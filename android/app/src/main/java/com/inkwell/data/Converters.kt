package com.inkwell.data

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Room type converters. `tools` (a String list) is stored as a JSON array string. */
class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String =
        Json.encodeToString(ListSerializer(String.serializer()), value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        Json.decodeFromString(ListSerializer(String.serializer()), value)
}
