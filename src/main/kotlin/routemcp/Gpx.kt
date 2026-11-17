package routemcp

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** Точка трека. Высота может отсутствовать, если BRouter её не знает. */
data class TrackPoint(val lat: Double, val lon: Double, val ele: Double?)

/**
 * Упрощение трека алгоритмом Дугласа–Пекера.
 *
 * Зачем: BRouter отдаёт точку каждые 10–30 м, на 100 км это тысячи точек.
 * Для навигации на часах столько не нужно, а гонять такой GPX через чат дорого.
 * Мы подбираем допуск (epsilon), пока точек не станет <= maxPoints.
 */
fun simplify(points: List<TrackPoint>, maxPoints: Int): List<TrackPoint> {
    if (points.size <= maxPoints) return points
    var epsilon = 3.0 // метры
    var result = douglasPeucker(points, epsilon)
    while (result.size > maxPoints) {
        epsilon *= 1.5
        result = douglasPeucker(points, epsilon)
    }
    return result
}

private fun douglasPeucker(points: List<TrackPoint>, epsilon: Double): List<TrackPoint> {
    // Переводим градусы в локальные метры (для участка в сотню км точности хватает)
    val lat0 = Math.toRadians(points.first().lat)
    val xs = DoubleArray(points.size) { points[it].lon * 111_320.0 * cos(lat0) }
    val ys = DoubleArray(points.size) { points[it].lat * 110_540.0 }

    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true

    // Итеративно через стек, а не рекурсией: на длинных треках рекурсия может упасть
    val stack = ArrayDeque<Pair<Int, Int>>()
    stack.addLast(0 to points.lastIndex)
    while (stack.isNotEmpty()) {
        val (start, end) = stack.removeLast()
        var maxDist = 0.0
        var index = -1
        for (i in start + 1 until end) {
            val d = perpendicularDistance(xs[i], ys[i], xs[start], ys[start], xs[end], ys[end])
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }
        if (index != -1 && maxDist > epsilon) {
            keep[index] = true
            stack.addLast(start to index)
            stack.addLast(index to end)
        }
    }
    return points.filterIndexed { i, _ -> keep[i] }
}

private fun perpendicularDistance(
    px: Double, py: Double,
    ax: Double, ay: Double,
    bx: Double, by: Double,
): Double {
    val dx = bx - ax
    val dy = by - ay
    val len = sqrt(dx * dx + dy * dy)
    if (len == 0.0) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
    return abs(dy * px - dx * py + bx * ay - by * ax) / len
}

/** Собираем GPX 1.1 руками — формат простой, XML-библиотека не нужна. */
fun toGpx(name: String, points: List<TrackPoint>): String = buildString {
    appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    appendLine("""<gpx version="1.1" creator="route-mcp" xmlns="http://www.topografix.com/GPX/1/1">""")
    appendLine("<trk><name>${escapeXml(name)}</name><trkseg>")
    for (p in points) {
        val lat = String.format(Locale.US, "%.6f", p.lat)
        val lon = String.format(Locale.US, "%.6f", p.lon)
        if (p.ele != null) {
            appendLine("""<trkpt lat="$lat" lon="$lon"><ele>${p.ele.toInt()}</ele></trkpt>""")
        } else {
            appendLine("""<trkpt lat="$lat" lon="$lon"/>""")
        }
    }
    appendLine("</trkseg></trk>")
    append("</gpx>")
}

private fun escapeXml(s: String) = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
