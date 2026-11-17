package routemcp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** Результат маршрутизации: полный трек + цифры, которые посчитал BRouter. */
data class RouteResult(
    val points: List<TrackPoint>,
    val distanceMeters: Double,
    val ascentMeters: Double,
    val timeSeconds: Double,
)

// --- Модели ответа BRouter (format=geojson) ---

@Serializable
data class BRouterResponse(val features: List<BRouterFeature>)

@Serializable
data class BRouterFeature(
    // Значения в properties приходят строками ("track-length": "12345"),
    // поэтому читаем их как JsonObject и парсим вручную
    val properties: JsonObject,
    val geometry: BRouterGeometry,
)

@Serializable
data class BRouterGeometry(
    // [[lon, lat, ele], ...] — внимание: сначала долгота!
    val coordinates: List<List<Double>>,
)

class BRouterApi(private val http: HttpClient, private val baseUrl: String) {

    suspend fun route(
        waypoints: List<Pair<Double, Double>>, // (lat, lon)
        profile: String,
        alternative: Int,
    ): RouteResult {
        // BRouter ждёт "lon,lat|lon,lat|..."
        val lonlats = waypoints.joinToString("|") { (lat, lon) ->
            String.format(Locale.US, "%.6f,%.6f", lon, lat)
        }

        val response = http.get("$baseUrl/brouter") {
            parameter("lonlats", lonlats)
            parameter("profile", profile)
            parameter("alternativeidx", alternative)
            parameter("format", "geojson")
        }
        // При ошибке BRouter отвечает простым текстом, например "unknown profile"
        if (!response.status.isSuccess()) {
            error("BRouter ${response.status.value}: ${response.bodyAsText().take(300)}")
        }

        val feature = response.body<BRouterResponse>().features.firstOrNull()
            ?: error("BRouter вернул пустой ответ")

        val points = feature.geometry.coordinates.map { c ->
            TrackPoint(lat = c[1], lon = c[0], ele = c.getOrNull(2))
        }
        fun prop(key: String) = feature.properties[key]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0

        return RouteResult(
            points = points,
            distanceMeters = prop("track-length"),
            ascentMeters = prop("filtered ascend"),
            timeSeconds = prop("total-time"),
        )
    }
}
