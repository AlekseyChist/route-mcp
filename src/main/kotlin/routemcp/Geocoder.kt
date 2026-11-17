package routemcp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Place(
    // Nominatim отдаёт координаты строками
    val lat: String,
    val lon: String,
    @SerialName("display_name") val displayName: String,
    val type: String? = null,
)

/**
 * Поиск координат по названию через Nominatim (OpenStreetMap).
 * Правила сервиса: не чаще 1 запроса в секунду и обязательный User-Agent.
 */
class Geocoder(private val http: HttpClient) {

    suspend fun search(query: String, limit: Int = 5): List<Place> =
        http.get("https://nominatim.openstreetmap.org/search") {
            parameter("q", query)
            parameter("format", "jsonv2")
            parameter("limit", limit)
            parameter("accept-language", "ru,sr-Latn,en")
            header("User-Agent", "route-mcp/0.1 (personal cycling route planner)")
        }.body()
}
