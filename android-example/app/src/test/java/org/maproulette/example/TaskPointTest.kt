package org.maproulette.example

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskPointTest {
    @Test
    fun spatialResponseCoordinatesBecomeLongitudeLatitudePoint() {
        val point = taskPoint(Json.parseToJsonElement("""{"lat":40.763385,"lng":-111.897134}""").jsonObject)!!
        assertEquals(-111.897134, point.longitude(), 0.000001)
        assertEquals(40.763385, point.latitude(), 0.000001)
    }

    @Test
    fun missingMalformedAndOutOfRangePointsAreSkipped() {
        listOf(
            """{}""",
            """{"lat":40}""",
            """{"lat":null,"lng":0}""",
            """{"lat":"40","lng":0}""",
            """{"lat":[],"lng":0}""",
            """{"lat":91,"lng":0}""",
            """{"lat":0,"lng":-181}""",
            """{"lat":1e999,"lng":0}""",
            """{"type":"Point","coordinates":[0,40]}""",
        ).forEach { json ->
            assertNull(json, taskPoint(Json.parseToJsonElement(json).jsonObject))
        }
    }
}
