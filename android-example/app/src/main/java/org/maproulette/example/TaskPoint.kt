package org.maproulette.example

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.geojson.Point

/** Spatial task summaries use lat/lng objects, not GeoJSON geometry objects. */
internal fun taskPoint(value: JsonObject): Point? {
    fun coordinate(key: String): Double? {
        val primitive = value[key] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.doubleOrNull?.takeIf { it.isFinite() }
    }

    val latitude = coordinate("lat") ?: return null
    val longitude = coordinate("lng") ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return Point.fromLngLat(longitude, latitude)
}
