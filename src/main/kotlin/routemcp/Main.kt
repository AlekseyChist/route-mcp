package routemcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.Locale

// Публичный сервер BRouter. Можно переопределить, если поднимешь свой: BROUTER_URL=http://localhost:17777
private val BROUTER_URL = System.getenv("BROUTER_URL") ?: "https://brouter.de"

// Куда складывать полные GPX на маке
private val ROUTES_DIR = File(System.getenv("ROUTES_DIR") ?: "${System.getProperty("user.home")}/Routes")

// Strava API: client_id/client_secret из приложения на strava.com/settings/api, refresh_token — из однократного OAuth.
// Все три нужны, чтобы find_popular_segments мог сам обновлять access_token; без них инструмент вернёт понятную ошибку.
private val STRAVA_CLIENT_ID = System.getenv("STRAVA_CLIENT_ID")
private val STRAVA_CLIENT_SECRET = System.getenv("STRAVA_CLIENT_SECRET")
private val STRAVA_REFRESH_TOKEN = System.getenv("STRAVA_REFRESH_TOKEN")
private val STRAVA_CACHE_FILE = File(
    System.getenv("STRAVA_CACHE_FILE") ?: "${System.getProperty("user.home")}/.secrets/route-mcp-strava-cache.json",
)

fun main() {
    // По stdout идёт JSON-RPC, и ничего кроме него: одна лишняя строка ломает транспорт.
    // Библиотеки об этом не знают (kotlin-logging печатает в stdout строку при инициализации),
    // поэтому настоящий stdout забираем себе, а System.out уводим в stderr.
    val protocolOut = FileOutputStream(FileDescriptor.out)
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))

    val http = HttpClient(CIO) {
        engine { requestTimeout = 60_000 } // длинные маршруты BRouter считает до ~десятка секунд
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    http.use {
        val server = Server(
            Implementation(name = "route-mcp", version = "0.1.0"),
            ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ),
        )
        server.registerGeocode(Geocoder(http))
        server.registerPlanRoute(BRouterApi(http, BROUTER_URL))
        server.registerFindPopularSegments(
            StravaApi(http, STRAVA_CLIENT_ID, STRAVA_CLIENT_SECRET, STRAVA_REFRESH_TOKEN, STRAVA_CACHE_FILE),
        )

        // stdio: Claude Desktop запускает jar и общается с ним через stdin/stdout (JSON-RPC)
        val transport = StdioServerTransport(
            input = System.`in`.asSource().buffered(),
            output = protocolOut.asSink().buffered(),
        )

        runBlocking {
            val session = server.createSession(transport)
            val done = Job()
            session.onClose { done.complete() }
            done.join()
        }
    }
}

private fun Server.registerGeocode(geocoder: Geocoder) {
    addTool(
        name = "geocode",
        description = "Find coordinates of a place by name using OpenStreetMap Nominatim. " +
            "Use it to turn place names (towns, peaks, cafes) into lat/lon before plan_route.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Place name, e.g. 'Avala, Serbia' or 'Kafana Kod Dragana, Ripanj'")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max results, default 5")
                }
            },
            required = listOf("query"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        val query = request.arguments?.get("query")?.jsonPrimitive?.content
            ?: return@addTool errorResult("Parameter 'query' is required")
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 5

        runCatching { geocoder.search(query, limit.coerceIn(1, 10)) }.fold(
            onSuccess = { places ->
                if (places.isEmpty()) {
                    CallToolResult(content = listOf(TextContent("Nothing found for '$query'")))
                } else {
                    val text = places.joinToString("\n") { "${it.lat}, ${it.lon} — ${it.displayName} (${it.type})" }
                    CallToolResult(content = listOf(TextContent(text)))
                }
            },
            onFailure = { errorResult("Geocoding failed: ${it.message}") },
        )
    }
}

private fun Server.registerPlanRoute(brouter: BRouterApi) {
    addTool(
        name = "plan_route",
        description = """
            Build a road-following route through waypoints with BRouter (OpenStreetMap).
            Returns distance, elevation gain, estimated time and a simplified GPX 1.1 track
            that can be passed as-is to COROS uploadRouteFile. The full-resolution GPX is also
            saved on the user's Mac.
            Profiles: fastbike (road bike, default), fastbike-lowtraffic (road, avoids busy roads),
            trekking (mixed surfaces, touring), safety (bike paths, quiet roads), shortest,
            hiking-mountain (on foot). For a loop, make the last waypoint equal to the first.
            Use 'avoid' to steer around a specific street or area (e.g. "that road has no shoulder")
            without fighting the waypoints — each entry is a circle the route won't enter.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("waypoints") {
                    put("type", "array")
                    put("minItems", 2)
                    put("description", "Ordered points the route must pass through")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("lat") { put("type", "number") }
                            putJsonObject("lon") { put("type", "number") }
                        }
                        putJsonArray("required") { add("lat"); add("lon") }
                    }
                }
                putJsonObject("avoid") {
                    put("type", "array")
                    put("description", "Circles the route must not enter, e.g. a street to skip")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("lat") { put("type", "number") }
                            putJsonObject("lon") { put("type", "number") }
                            putJsonObject("radius_m") {
                                put("type", "number")
                                put("description", "Radius in meters, default 300")
                            }
                        }
                        putJsonArray("required") { add("lat"); add("lon") }
                    }
                }
                putJsonObject("profile") {
                    put("type", "string")
                    put("description", "BRouter profile, default fastbike")
                }
                putJsonObject("alternative") {
                    put("type", "integer")
                    put("description", "Alternative route index 0-3, default 0")
                }
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Route name (GPX track name and file name)")
                }
                putJsonObject("max_points") {
                    put("type", "integer")
                    put("description", "Max points in the returned GPX, default 350")
                }
            },
            required = listOf("waypoints"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = true),
    ) { request ->
        val args = request.arguments ?: JsonObject(emptyMap())

        val waypoints = runCatching { parseWaypoints(args["waypoints"]) }.getOrElse {
            return@addTool errorResult("Bad 'waypoints': ${it.message}. Expected [{\"lat\":44.8,\"lon\":20.4}, ...]")
        }
        if (waypoints.size < 2) return@addTool errorResult("Need at least 2 waypoints")

        val avoid = runCatching { parseAvoid(args["avoid"]) }.getOrElse {
            return@addTool errorResult("Bad 'avoid': ${it.message}")
        }

        val profile = args["profile"]?.jsonPrimitive?.content ?: "fastbike"
        val alternative = args["alternative"]?.jsonPrimitive?.intOrNull ?: 0
        val name = args["name"]?.jsonPrimitive?.content ?: "route"
        val maxPoints = (args["max_points"]?.jsonPrimitive?.intOrNull ?: 350).coerceIn(50, 3000)

        val route = runCatching { brouter.route(waypoints, profile, alternative.coerceIn(0, 3), avoid) }
            .getOrElse { return@addTool errorResult("Routing failed: ${it.message}") }

        // Полный трек — в файл на маке
        val savedPath = runCatching {
            ROUTES_DIR.mkdirs()
            val file = File(ROUTES_DIR, "${safeFileName(name)}.gpx")
            file.writeText(toGpx(name, route.points))
            file.absolutePath
        }.getOrElse { "not saved (${it.message})" }

        // Упрощённый — в ответ, чтобы его можно было сразу залить в COROS
        val simplified = simplify(route.points, maxPoints)
        val elevations = route.points.mapNotNull { it.ele }

        val summary = buildString {
            appendLine("Route: $name (profile $profile, alternative $alternative)")
            appendLine("Distance: ${fmt(route.distanceMeters / 1000)} km")
            appendLine("Elevation gain: ${route.ascentMeters.toInt()} m")
            if (elevations.isNotEmpty()) {
                appendLine("Elevation min/max: ${elevations.min().toInt()} / ${elevations.max().toInt()} m")
            }
            appendLine("BRouter time estimate: ${formatDuration(route.timeSeconds)}")
            appendLine("Points: ${route.points.size} full → ${simplified.size} in GPX below")
            append("Full GPX saved to: $savedPath")
        }

        CallToolResult(
            content = listOf(
                TextContent(summary),
                TextContent(toGpx(name, simplified)),
            ),
        )
    }
}

private fun Server.registerFindPopularSegments(strava: StravaApi) {
    addTool(
        name = "find_popular_segments",
        description = """
            Find the most popular Strava segments near a point (Strava's own Segments Explore
            ranking — not the heatmap, which Strava does not expose to third-party apps).
            A good proxy for "where people actually ride/run", but discrete: segments are
            individual stretches (often climbs), not a continuous surface like a heatmap.
            Returns each segment's distance, average grade, climb category and start/end
            coordinates — feed those straight into plan_route's waypoints to route through it.
            activity_type: riding (default) or running.
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("lat") { put("type", "number"); put("description", "Center latitude") }
                putJsonObject("lon") { put("type", "number"); put("description", "Center longitude") }
                putJsonObject("radius_km") {
                    put("type", "number")
                    put("description", "Search radius around the point in km, default 5 (max 25)")
                }
                putJsonObject("activity_type") {
                    put("type", "string")
                    put("description", "riding (default) or running")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max segments to return, default 10")
                }
            },
            required = listOf("lat", "lon"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        if (!strava.isConfigured) {
            return@addTool errorResult(
                "Strava is not configured: set STRAVA_CLIENT_ID, STRAVA_CLIENT_SECRET and STRAVA_REFRESH_TOKEN " +
                    "in the server's environment (see README.md).",
            )
        }

        val args = request.arguments ?: JsonObject(emptyMap())
        val lat = args["lat"]?.jsonPrimitive?.double
            ?: return@addTool errorResult("Parameter 'lat' is required")
        val lon = args["lon"]?.jsonPrimitive?.double
            ?: return@addTool errorResult("Parameter 'lon' is required")
        val radiusKm = (args["radius_km"]?.jsonPrimitive?.double ?: 5.0).coerceIn(0.5, 25.0)
        val activityType = args["activity_type"]?.jsonPrimitive?.content ?: "riding"
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 20)

        runCatching { strava.exploreSegments(lat, lon, radiusKm, activityType) }.fold(
            onSuccess = { segments ->
                if (segments.isEmpty()) {
                    CallToolResult(content = listOf(TextContent("No segments found within ${fmt(radiusKm)} km")))
                } else {
                    val text = segments.take(limit).joinToString("\n\n") { s ->
                        val start = s.startLatLng.takeIf { it.size == 2 }?.let { "${it[0]}, ${it[1]}" } ?: "?"
                        val end = s.endLatLng.takeIf { it.size == 2 }?.let { "${it[0]}, ${it[1]}" } ?: "?"
                        "${s.name} (${climbCategoryName(s.climbCategory)})\n" +
                            "  ${fmt(s.distance / 1000)} km, avg grade ${fmt(s.avgGrade)}%, elevation +${s.elevDifference.toInt()} m\n" +
                            "  start: $start → end: $end"
                    }
                    CallToolResult(content = listOf(TextContent(text)))
                }
            },
            onFailure = { errorResult("Strava segments failed: ${it.message}") },
        )
    }
}

/** Модель иногда присылает массив строкой — принимаем оба варианта. */
private fun parseWaypoints(element: JsonElement?): List<Pair<Double, Double>> {
    requireNotNull(element) { "missing" }
    val array = if (element is JsonPrimitive && element.isString) {
        Json.parseToJsonElement(element.content).jsonArray
    } else {
        element.jsonArray
    }
    return array.map { item ->
        val obj = item.jsonObject
        val lat = obj.getValue("lat").jsonPrimitive.double
        val lon = obj.getValue("lon").jsonPrimitive.double
        require(lat in -90.0..90.0 && lon in -180.0..180.0) { "out of range: $lat, $lon" }
        lat to lon
    }
}

/** 'avoid' необязателен: отсутствие или пустой массив — просто нет зон-исключений. */
private fun parseAvoid(element: JsonElement?): List<Triple<Double, Double, Double>> {
    if (element == null) return emptyList()
    val array = if (element is JsonPrimitive && element.isString) {
        Json.parseToJsonElement(element.content).jsonArray
    } else {
        element.jsonArray
    }
    return array.map { item ->
        val obj = item.jsonObject
        val lat = obj.getValue("lat").jsonPrimitive.double
        val lon = obj.getValue("lon").jsonPrimitive.double
        val radius = obj["radius_m"]?.jsonPrimitive?.double ?: 300.0
        require(lat in -90.0..90.0 && lon in -180.0..180.0) { "out of range: $lat, $lon" }
        Triple(lat, lon, radius.coerceIn(20.0, 5000.0))
    }
}

private fun errorResult(message: String) =
    CallToolResult(content = listOf(TextContent(message)), isError = true)

private fun safeFileName(name: String) =
    name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').ifEmpty { "route" }

private fun fmt(value: Double) = String.format(Locale.US, "%.1f", value)

private fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    return "%d:%02d".format(total / 3600, (total % 3600) / 60)
}
