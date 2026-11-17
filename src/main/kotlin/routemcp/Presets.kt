package routemcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class WaypointDto(val lat: Double, val lon: Double)

@Serializable
data class AvoidDto(val lat: Double, val lon: Double, @SerialName("radius_m") val radiusM: Double = 300.0)

@Serializable
data class RoutePreset(
    val description: String = "",
    val waypoints: List<WaypointDto>,
    val avoid: List<AvoidDto> = emptyList(),
    val profile: String = "fastbike",
)

@Serializable
private data class PresetsFile(val routes: Map<String, RoutePreset> = emptyMap())

/**
 * Готовые маршруты — те самые waypoints+avoid, которые обычно собираются вручную через
 * geocode/curl, но после первой настройки их хочется просто называть по имени.
 * Файл правится руками в любом текстовом редакторе; на первом запуске, если файла нет,
 * создаём его с одним примером — его можно удалить/отредактировать.
 */
class PresetStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun list(): Map<String, RoutePreset> =
        runCatching { json.decodeFromString<PresetsFile>(file.readText()).routes }.getOrDefault(emptyMap())

    fun get(name: String): RoutePreset? = list()[name]

    fun ensureSeeded() {
        if (file.exists()) return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(PresetsFile(routes = mapOf(SEED_NAME to SEED_PRESET))))
        }
    }

    companion object {
        private const val SEED_NAME = "avala-rakovica-loop"
        private val SEED_PRESET = RoutePreset(
            description = "Luke Ćelovića Trebinjca 11 → Avala (via Rakovica) → Avalska → " +
                "Mejbl Grujić → Zuce loop → Vojvode Stepe → Autokomanda → Birčaninova → home. " +
                "~64 km, ~900 m ascent, fastbike.",
            waypoints = listOf(
                WaypointDto(44.8060, 20.4535), // дом
                WaypointDto(44.6891, 20.5159), // Авала
                WaypointDto(44.6787, 20.5064), // Avalska
                WaypointDto(44.6594, 20.5194), // Mejbl Grujić
                WaypointDto(44.6813, 20.5513), // Zuce (юг)
                WaypointDto(44.6963, 20.5536), // Zuce (север)
                WaypointDto(44.7542, 20.4913), // Vojvode Stepe / Banjica
                WaypointDto(44.7898, 20.4666), // Autokomanda
                WaypointDto(44.8052, 20.4583), // Birčaninova
                WaypointDto(44.8060, 20.4535), // дом
            ),
            avoid = listOf(AvoidDto(44.7256, 20.4952, 400.0)), // Bojana Stupice — мойка, грязь на дороге
            profile = "fastbike",
        )
    }
}
