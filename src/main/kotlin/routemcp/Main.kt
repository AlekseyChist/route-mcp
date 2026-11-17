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

        val profile = args["profile"]?.jsonPrimitive?.content ?: "fastbike"
        val alternative = args["alternative"]?.jsonPrimitive?.intOrNull ?: 0
        val name = args["name"]?.jsonPrimitive?.content ?: "route"
        val maxPoints = (args["max_points"]?.jsonPrimitive?.intOrNull ?: 350).coerceIn(50, 3000)

        val route = runCatching { brouter.route(waypoints, profile, alternative.coerceIn(0, 3)) }
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

private fun errorResult(message: String) =
    CallToolResult(content = listOf(TextContent(message)), isError = true)

private fun safeFileName(name: String) =
    name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').ifEmpty { "route" }

private fun fmt(value: Double) = String.format(Locale.US, "%.1f", value)

private fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    return "%d:%02d".format(total / 3600, (total % 3600) / 60)
}
