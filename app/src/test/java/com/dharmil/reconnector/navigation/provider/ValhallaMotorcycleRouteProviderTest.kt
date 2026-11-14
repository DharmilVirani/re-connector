package com.dharmil.reconnector.navigation.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ValhallaMotorcycleRouteProviderTest {
    @Test
    fun parsesPolylineAndTurnInstructions() {
        val response = """
            {"trip":{"status":0,"legs":[
              {"shape":"A?A?A?","maneuvers":[
                {"begin_shape_index":0,"instruction":"Start","length":0.01}
              ]},
              {"shape":"??A?","maneuvers":[
                {"begin_shape_index":1,"instruction":"Turn right","length":0.25}
              ]}
            ]}}
        """.trimIndent()

        val route = ValhallaMotorcycleRouteProvider().parseResponse(response, "Test destination")

        assertEquals("Test destination", route.destinationName)
        assertEquals(4, route.points.size)
        assertEquals(2, route.maneuvers.size)
        assertEquals(0, route.maneuvers[0].pointIndex)
        assertEquals(10, route.maneuvers[0].distanceMeters)
        assertEquals("Turn right", route.maneuvers[1].instruction)
        assertEquals(3, route.maneuvers[1].pointIndex)
        assertEquals(250, route.maneuvers[1].distanceMeters)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsValhallaRouteError() {
        ValhallaMotorcycleRouteProvider().parseResponse(
            """{"trip":{"status":442,"status_message":"No route"}}""",
            "Test destination",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsManeuverOutsideLegGeometry() {
        ValhallaMotorcycleRouteProvider().parseResponse(
            """{"trip":{"status":0,"legs":[{"shape":"A?A?","maneuvers":[{"begin_shape_index":5,"instruction":"Turn","length":0.2}]}]}}""",
            "Test destination",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNullInstruction() {
        ValhallaMotorcycleRouteProvider().parseResponse(
            routeWithManeuver("\"instruction\":null,\"begin_shape_index\":0,\"length\":0.2"),
            "Test",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonStringInstruction() {
        ValhallaMotorcycleRouteProvider().parseResponse(
            routeWithManeuver("\"instruction\":7,\"begin_shape_index\":0,\"length\":0.2"),
            "Test",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFractionalManeuverIndex() {
        ValhallaMotorcycleRouteProvider().parseResponse(
            routeWithManeuver("\"instruction\":\"Turn\",\"begin_shape_index\":1.9,\"length\":0.2"),
            "Test",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsStringManeuverIndex() {
        ValhallaMotorcycleRouteProvider().parseResponse(
            routeWithManeuver("\"instruction\":\"Turn\",\"begin_shape_index\":\"1\",\"length\":0.2"),
            "Test",
        )
    }

    private fun routeWithManeuver(maneuverFields: String): String =
        """{"trip":{"status":0,"legs":[{"shape":"A?A?","maneuvers":[{$maneuverFields}]}]}}"""
}
