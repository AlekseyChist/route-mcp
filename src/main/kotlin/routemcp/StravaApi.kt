package routemcp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import kotlin.math.cos

// --- Модели ответа Strava ---

@Serializable
data class StravaSegment(
    val id: Long,
    val name: String,
    @SerialName("climb_category") val climbCategory: Int = 0,
    @SerialName("avg_grade") val avgGrade: Double = 0.0,
    @SerialName("elev_difference") val elevDifference: Double = 0.0,
    val distance: Double = 0.0,
    @SerialName("start_latlng") val startLatLng: List<Double> = emptyList(),
    @SerialName("end_latlng") val endLatLng: List<Double> = emptyList(),
)

@Serializable
private data class StravaExploreResponse(val segments: List<StravaSegment> = emptyList())

@Serializable
private data class StravaTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long,
)

// То, что мы сами сохраняем между запусками, чтобы не дёргать refresh на каждый старт
// и не терять новый refresh_token, если Strava его обновит.
@Serializable
private data class StravaTokenCache(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
)

fun climbCategoryName(category: Int) = when (category) {
    1 -> "Cat 4"
    2 -> "Cat 3"
    3 -> "Cat 2"
    4 -> "Cat 1"
    5 -> "HC"
    else -> "NC"
}

/**
 * Strava Segments Explore: самые популярные сегменты в прямоугольнике вокруг точки.
 * Это не хитмэп (тот закрыт для сторонних приложений), а рейтинг сегментов самой Strava —
 * близкий суррогат «где люди ездят», но дискретный: между сегментами ничего нет.
 *
 * Авторизация — OAuth refresh_token flow: client_id/client_secret не меняются,
 * а access_token короткоживущий (6 ч) и обновляется автоматически. Новый refresh_token
 * (Strava иногда присылает его при обновлении) сохраняется в [cacheFile], чтобы выжить
 * между перезапусками сервера.
 */
class StravaApi(
    private val http: HttpClient,
    private val clientId: String?,
    private val clientSecret: String?,
    initialRefreshToken: String?,
    private val cacheFile: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private var accessToken: String? = null
    private var expiresAt: Long = 0
    private var refreshToken: String? = initialRefreshToken

    init {
        runCatching { json.decodeFromString<StravaTokenCache>(cacheFile.readText()) }
            .onSuccess { cache ->
                accessToken = cache.accessToken
                expiresAt = cache.expiresAt
                refreshToken = cache.refreshToken
            }
    }

    val isConfigured: Boolean
        get() = !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank() && !refreshToken.isNullOrBlank()

    suspend fun exploreSegments(lat: Double, lon: Double, radiusKm: Double, activityType: String): List<StravaSegment> {
        val response = authorizedGet("https://www.strava.com/api/v3/segments/explore") {
            parameter("bounds", bounds(lat, lon, radiusKm))
            parameter("activity_type", activityType)
        }
        return response.body<StravaExploreResponse>().segments
    }

    /** GET с токеном; если словили 401 (например, access_token истёк раньше ожидаемого), один раз обновляем и повторяем. */
    private suspend fun authorizedGet(
        url: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): io.ktor.client.statement.HttpResponse {
        suspend fun attempt(token: String) = http.get(url) {
            header("Authorization", "Bearer $token")
            block()
        }

        var response = attempt(freshAccessToken())
        if (response.status.value == 401) {
            response = attempt(refresh())
        }
        if (response.status.value == 401) {
            // Токен свежий и scope=read подтверждён при авторизации, но Strava всё равно отдаёт 401:
            // с 1 сентября 2026 /segments/explore (и ряд других эндпоинтов) задеприцированы для всех,
            // кроме приложений с одобренным Extended Access. Это не лечится повторной авторизацией.
            error(
                "Strava 401 после обновления токена — скорее всего, этот эндпоинт требует Extended Access " +
                    "(Strava задеприцировала /segments/explore для обычного тира с 1 сентября 2026). " +
                    "Прямой запрос сегмента по id (/segments/{id}) при этом продолжает работать.",
            )
        }
        if (!response.status.isSuccess()) {
            error("Strava ${response.status.value}: ${response.bodyAsText().take(300)}")
        }
        return response
    }

    private suspend fun freshAccessToken(): String {
        val token = accessToken
        val now = System.currentTimeMillis() / 1000
        if (token != null && now < expiresAt - 60) return token
        return refresh()
    }

    private suspend fun refresh(): String {
        val id = requireNotNull(clientId) { "STRAVA_CLIENT_ID не задан" }
        val secret = requireNotNull(clientSecret) { "STRAVA_CLIENT_SECRET не задан" }
        val current = requireNotNull(refreshToken) { "STRAVA_REFRESH_TOKEN не задан" }

        val response = http.submitForm(
            url = "https://www.strava.com/oauth/token",
            formParameters = Parameters.build {
                append("client_id", id)
                append("client_secret", secret)
                append("grant_type", "refresh_token")
                append("refresh_token", current)
            },
        )
        if (!response.status.isSuccess()) {
            error("Strava token refresh ${response.status.value}: ${response.bodyAsText().take(300)}")
        }

        val tokens = response.body<StravaTokenResponse>()
        accessToken = tokens.accessToken
        expiresAt = tokens.expiresAt
        refreshToken = tokens.refreshToken

        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(json.encodeToString(StravaTokenCache(tokens.accessToken, tokens.refreshToken, tokens.expiresAt)))
            // В кэше лежит refresh_token — долгоживущий секрет. writeText() создаёт файл с умолчаниями
            // системы (обычно 644), это читаемо всеми в системе; запираем сразу после записи.
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(cacheFile.toPath(), PosixFilePermissions.fromString("rw-------"))
            }
        }
        return tokens.accessToken
    }

    /** Прямоугольник вокруг точки в формате Strava: sw_lat,sw_lng,ne_lat,ne_lng. */
    private fun bounds(lat: Double, lon: Double, radiusKm: Double): String {
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        return listOf(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
            .joinToString(",") { String.format(Locale.US, "%.6f", it) }
    }
}
