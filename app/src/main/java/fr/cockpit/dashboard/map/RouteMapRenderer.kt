package fr.cockpit.dashboard.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import fr.cockpit.dashboard.navigation.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** Real OSM tiles and guidance overlays, shared by the phone and Android Auto surfaces. */
class RouteMapRenderer(context: Context, private val onInvalidate: () -> Unit) {
    private val defaultDensity = context.resources.displayMetrics.density.coerceIn(1f, 2f)
    private var density = defaultDensity
    private val tileStore = MapTileStore(context, onInvalidate)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val background = Color.rgb(21, 31, 34)
    private val cyan = Color.rgb(103, 224, 239)
    private val nightFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        -.24f, -.49f, -.07f, 0f, 230f,
        -.24f, -.49f, -.07f, 0f, 241f,
        -.24f, -.49f, -.07f, 0f, 244f,
        0f, 0f, 0f, 1f, 0f,
    )))
    private val dayFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(.68f) })
    private var closed = false
    private var lastBearing: Float? = null
    private var framedRoute: List<GeoPoint>? = null
    private var framedWidth = 0
    private var framedHeight = 0
    private var framedDensity = 0f
    private var framedCamera: MapCamera? = null

    var zoom: Int = 15
        set(value) {
            val next = value.coerceIn(0, 18)
            if (field != next) {
                field = next
                onInvalidate()
            }
        }

    fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        latitude: Double?,
        longitude: Double?,
        routePoints: List<GeoPoint>,
        bearing: Float? = null,
        camera: MapCamera? = null,
        headingUp: Boolean = false,
        nightMode: Boolean = true,
        accuracyMeters: Float? = null,
        destination: GeoPoint? = null,
        progressFraction: Float = 0f,
        densityOverride: Float? = null,
        orientationBearing: Float? = null,
    ) {
        if (closed || width <= 0 || height <= 0) return
        density = densityOverride?.takeIf { it.isFinite() && it > 0 }?.coerceIn(.75f, 3f) ?: defaultDensity
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(if (nightMode) background else Color.rgb(232, 237, 230))
        val fix = if (latitude != null && longitude != null) GeoPoint(latitude, longitude).takeIf(MapProjection::isValid) else null
        val viewport = camera?.takeIf { MapProjection.isValid(it.center) && it.zoom.isFinite() }
            ?: fix?.let { MapCamera(it, zoom.toFloat()) }
            ?: frameRoute(routePoints, width, height)
        if (viewport == null) {
            tileStore.setVisible(emptySet())
            drawWaiting(canvas, width, height, nightMode)
            drawAttribution(canvas, width, height)
            canvas.restoreToCount(saved)
            return
        }
        val cameraZoom = viewport.zoom.coerceIn(MapProjection.MIN_ZOOM, MapProjection.MAX_ZOOM)
        val tileZoom = floor(cameraZoom).toInt()
        val tileCount = 1 shl tileZoom
        val tileSize = 256.0 * density * 2.0.pow((cameraZoom - tileZoom).toDouble())
        val world = tileCount * tileSize
        val center = MapProjection.project(viewport.center, world)
        val left = center.x - width / 2.0
        val top = center.y - height / 2.0
        bearing?.takeIf(Float::isFinite)?.let { lastBearing = it }
        val angle = if (headingUp) orientationBearing?.takeIf(Float::isFinite) ?: lastBearing ?: 0f else 0f
        val radians = angle * PI / 180
        val cosAngle = cos(radians)
        val sinAngle = sin(radians)
        val halfWidth = abs(cosAngle) * width / 2 + abs(sinAngle) * height / 2
        val halfHeight = abs(sinAngle) * width / 2 + abs(cosAngle) * height / 2
        val minX = floor((center.x - halfWidth) / tileSize).toInt()
        val maxX = floor((center.x + halfWidth) / tileSize).toInt()
        val minY = floor((center.y - halfHeight) / tileSize).toInt().coerceAtLeast(0)
        val maxY = floor((center.y + halfHeight) / tileSize).toInt().coerceAtMost(tileCount - 1)
        // Keep downloads limited to tiles intersecting the rotated viewport, never prefetch a region.
        val tileCoordinates = buildList {
            for (y in minY..maxY) for (x in minX..maxX) {
                val dx = (x + .5) * tileSize - center.x
                val dy = (y + .5) * tileSize - center.y
                val screenX = dx * cosAngle + dy * sinAngle
                val screenY = -dx * sinAngle + dy * cosAngle
                val radius = tileSize / 2 * (abs(cosAngle) + abs(sinAngle))
                if (abs(screenX) <= width / 2 + radius && abs(screenY) <= height / 2 + radius) add(x to y)
            }
        }
        tileStore.setVisible(tileCoordinates.map { (x, y) -> TileKey(tileZoom, Math.floorMod(x, tileCount), y) }.toSet())
        val rotated = canvas.save()
        canvas.rotate(-angle, width / 2f, height / 2f)
        var drawnTiles = 0
        tileCoordinates.forEach { (x, y) ->
            val xScreen = (x * tileSize - left).toFloat()
            val yScreen = (y * tileSize - top).toFloat()
            val rect = RectF(xScreen, yScreen, (xScreen + tileSize).toFloat(), (yScreen + tileSize).toFloat())
            val bitmap = tileStore.tile(TileKey(tileZoom, Math.floorMod(x, tileCount), y))
            paint.reset()
            if (bitmap == null) {
                paint.color = if (nightMode) Color.rgb(35, 47, 49) else Color.rgb(210, 219, 207)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                canvas.drawRect(rect, paint)
            } else {
                paint.isFilterBitmap = true
                paint.colorFilter = if (nightMode) nightFilter else dayFilter
                canvas.drawBitmap(bitmap, null, rect, paint)
                drawnTiles++
            }
        }
        fun screen(point: GeoPoint): Pair<Float, Float> {
            val projected = MapProjection.project(point, world)
            return (MapProjection.wrappedDelta(projected.x, center.x, world) + width / 2).toFloat() to
                (projected.y - top).toFloat()
        }
        val path = Path()
        var previousX: Float? = null
        routePoints.filter(MapProjection::isValid).forEach { point ->
            val (x, y) = screen(point)
            if (previousX == null || abs(x - previousX!!) > world / 2) path.moveTo(x, y) else path.lineTo(x, y)
            previousX = x
        }
        drawRoute(canvas, path, progressFraction, nightMode)
        val target = destination?.takeIf(MapProjection::isValid) ?: routePoints.lastOrNull()?.takeIf(MapProjection::isValid)
        routePoints.firstOrNull()?.takeIf(MapProjection::isValid)?.let { start ->
            val (x, y) = screen(start)
            paint.reset()
            paint.isAntiAlias = true
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 5f * density, paint)
            paint.color = Color.rgb(24, 58, 65)
            canvas.drawCircle(x, y, 2.5f * density, paint)
        }
        target?.let {
            val (x, y) = screen(it)
            drawDestination(canvas, x, y, angle)
        }
        fix?.let {
            val (x, y) = screen(it)
            val accuracy = accuracyMeters?.takeIf { value -> value.isFinite() && value > 0 }
            if (accuracy != null) {
                val radius = (accuracy / MapProjection.metersPerPixel(it.latitude, cameraZoom, density))
                    .coerceAtMost(maxOf(width, height).toDouble()).toFloat()
                paint.reset()
                paint.isAntiAlias = true
                paint.color = Color.argb(22, 99, 225, 240)
                canvas.drawCircle(x, y, radius, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density
                paint.color = Color.argb(65, 99, 225, 240)
                canvas.drawCircle(x, y, radius, paint)
            }
            drawPosition(canvas, x, y, bearing)
        }
        canvas.restoreToCount(rotated)
        if (drawnTiles == 0) {
            drawBadge(canvas, width / 2f, height * .70f,
                if (tileStore.hasVisibleFailure()) "Fond de carte indisponible" else "Chargement de la carte…", 12f)
            if (tileStore.hasVisibleFailure()) drawBadge(canvas, width / 2f, height * .70f + 29f * density,
                "Vérifiez la connexion Internet", 10f)
        } else if (tileStore.hasVisibleFailure()) {
            drawBadge(canvas, width / 2f, height * .75f, "Certaines zones ne sont pas chargées", 10f)
        }
        drawCompass(canvas, width - 25f * density, height - 48f * density, angle)
        drawScale(canvas, width, height, viewport.center.latitude, cameraZoom)
        drawAttribution(canvas, width, height)
        canvas.restoreToCount(saved)
    }

    private fun frameRoute(routePoints: List<GeoPoint>, width: Int, height: Int): MapCamera? {
        if (framedRoute !== routePoints || framedWidth != width || framedHeight != height || framedDensity != density) {
            framedCamera = MapProjection.fitRoute(routePoints, width, height, density)
            framedRoute = routePoints
            framedWidth = width
            framedHeight = height
            framedDensity = density
        }
        return framedCamera
    }

    private fun drawRoute(canvas: Canvas, route: Path, progressFraction: Float, nightMode: Boolean) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 12f * density
        paint.color = Color.argb(100, 4, 17, 22)
        canvas.drawPath(route, paint)
        paint.strokeWidth = 8f * density
        paint.color = if (nightMode) Color.rgb(112, 137, 144) else Color.rgb(156, 168, 168)
        canvas.drawPath(route, paint)
        val remaining = if (progressFraction.isFinite() && progressFraction > 0f) {
            val measure = PathMeasure(route, false)
            Path().also { measure.getSegment(measure.length * progressFraction.coerceIn(0f, 1f), measure.length, it, true) }
        } else route
        paint.strokeWidth = 9f * density
        paint.color = if (nightMode) Color.rgb(13, 87, 107) else Color.WHITE
        canvas.drawPath(remaining, paint)
        paint.strokeWidth = 5f * density
        paint.color = if (nightMode) cyan else Color.rgb(0, 120, 165)
        canvas.drawPath(remaining, paint)
        paint.strokeWidth = 1.5f * density
        paint.color = if (nightMode) Color.rgb(183, 248, 250) else Color.rgb(103, 224, 239)
        canvas.drawPath(remaining, paint)
    }

    private fun drawDestination(canvas: Canvas, x: Float, y: Float, angle: Float) {
        val saved = canvas.save()
        canvas.rotate(angle, x, y)
        paint.reset()
        paint.isAntiAlias = true
        paint.color = Color.argb(85, 0, 0, 0)
        canvas.drawOval(RectF(x - 12f * density, y - 4f * density, x + 12f * density, y + 4f * density), paint)
        val pin = Path().apply {
            moveTo(x, y)
            cubicTo(x - 6f * density, y - 9f * density, x - 13f * density, y - 15f * density, x - 13f * density, y - 23f * density)
            cubicTo(x - 13f * density, y - 40f * density, x + 13f * density, y - 40f * density, x + 13f * density, y - 23f * density)
            cubicTo(x + 13f * density, y - 15f * density, x + 6f * density, y - 9f * density, x, y)
            close()
        }
        paint.color = Color.rgb(238, 248, 230)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(pin, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(181, 243, 110)
        canvas.drawPath(pin, paint)
        paint.color = Color.rgb(23, 45, 34)
        canvas.drawRoundRect(RectF(x - 5f * density, y - 29f * density, x + 5f * density, y - 19f * density), 2f * density, 2f * density, paint)
        canvas.restoreToCount(saved)
    }

    private fun drawPosition(canvas: Canvas, x: Float, y: Float, bearing: Float?) {
        paint.reset()
        paint.isAntiAlias = true
        paint.color = Color.argb(35, 103, 224, 239)
        canvas.drawCircle(x, y, 27f * density, paint)
        paint.color = Color.argb(45, 103, 224, 239)
        canvas.drawCircle(x, y, 20f * density, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, 12f * density, paint)
        if (bearing != null && bearing.isFinite()) {
            val save = canvas.save()
            canvas.rotate(bearing, x, y)
            val arrow = Path().apply {
                moveTo(x, y - 19f * density)
                lineTo(x + 12f * density, y + 12f * density)
                lineTo(x, y + 6f * density)
                lineTo(x - 12f * density, y + 12f * density)
                close()
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f * density
            paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(arrow, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(19, 116, 145)
            canvas.drawPath(arrow, paint)
            canvas.restoreToCount(save)
        } else {
            paint.color = Color.rgb(19, 116, 145)
            canvas.drawCircle(x, y, 8f * density, paint)
        }
    }

    private fun drawWaiting(canvas: Canvas, width: Int, height: Int, nightMode: Boolean) {
        paint.reset()
        paint.strokeWidth = 1f
        paint.color = if (nightMode) Color.rgb(31, 45, 48) else Color.rgb(219, 228, 215)
        val step = 36f * density
        var x = width / 2f % step
        while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), paint); x += step }
        var y = height / 2f % step
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, paint); y += step }
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = if (nightMode) Color.rgb(47, 69, 70) else Color.rgb(190, 207, 190)
        canvas.drawCircle(width / 2f, height / 2f - 25f * density, 42f * density, paint)
        canvas.drawCircle(width / 2f, height / 2f - 25f * density, 61f * density, paint)
        drawBadge(canvas, width / 2f, height / 2f + 5f * density, "Position GPS en attente", 13f)
        drawBadge(canvas, width / 2f, height / 2f + 34f * density, "Démarrez le suivi pour afficher la carte", 10f)
    }

    private fun drawCompass(canvas: Canvas, x: Float, y: Float, angle: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.color = Color.argb(235, 24, 35, 38)
        canvas.drawCircle(x, y, 17f * density, paint)
        val saved = canvas.save()
        canvas.rotate(-angle, x, y)
        val needle = Path().apply {
            moveTo(x, y - 10f * density)
            lineTo(x + 4f * density, y + 2f * density)
            lineTo(x, y)
            lineTo(x - 4f * density, y + 2f * density)
            close()
        }
        paint.color = Color.rgb(181, 243, 110)
        canvas.drawPath(needle, paint)
        canvas.restoreToCount(saved)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 7f * density
        paint.color = Color.WHITE
        canvas.drawText("N", x, y + 11f * density, paint)
    }

    private fun drawScale(canvas: Canvas, width: Int, height: Int, latitude: Double, zoom: Float) {
        if (width < 280f * density) return
        val metersPerPixel = MapProjection.metersPerPixel(latitude, zoom, density)
        val maxMeters = 78f * density * metersPerPixel
        val base = 10.0.pow(floor(log10(maxMeters)))
        val meters = listOf(1.0, 2.0, 5.0).map { it * base }.lastOrNull { it <= maxMeters } ?: base
        val length = (meters / metersPerPixel).toFloat()
        val left = 15f * density
        val bottom = height - 16f * density
        paint.reset()
        paint.isAntiAlias = true
        paint.color = Color.argb(230, 24, 35, 38)
        canvas.drawRoundRect(RectF(left - 7f * density, bottom - 26f * density,
            left + length + 7f * density, bottom + 7f * density), 5f * density, 5f * density, paint)
        paint.color = Color.WHITE
        paint.strokeWidth = 1.5f * density
        canvas.drawLine(left, bottom, left + length, bottom, paint)
        canvas.drawLine(left, bottom - 4f * density, left, bottom, paint)
        canvas.drawLine(left + length, bottom - 4f * density, left + length, bottom, paint)
        paint.textSize = 9f * density
        paint.textAlign = Paint.Align.CENTER
        val label = if (meters >= 1000) "${(meters / 1000).toInt()} km" else "${meters.toInt()} m"
        canvas.drawText(label, left + length / 2, bottom - 9f * density, paint)
    }

    private fun drawAttribution(canvas: Canvas, width: Int, height: Int) {
        val text = "© OpenStreetMap contributors"
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = 9f * density
        paint.textAlign = Paint.Align.LEFT
        val textWidth = paint.measureText(text)
        val left = (width - textWidth - 13f * density).coerceAtLeast(0f)
        paint.color = Color.argb(225, 24, 35, 38)
        canvas.drawRoundRect(RectF(left, height - 20f * density, width.toFloat() + 5f, height.toFloat() + 5f), 5f * density, 5f * density, paint)
        paint.color = Color.rgb(223, 235, 228)
        canvas.drawText(text, left + 6f * density, height - 7f * density, paint)
    }

    private fun drawBadge(canvas: Canvas, x: Float, y: Float, text: String, size: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = size * density
        val textWidth = paint.measureText(text)
        val rectangle = RectF(x - textWidth / 2 - 12f * density, y - 16f * density,
            x + textWidth / 2 + 12f * density, y + 12f * density)
        paint.color = Color.argb(235, 24, 35, 38)
        canvas.drawRoundRect(rectangle, 10f * density, 10f * density, paint)
        paint.color = Color.rgb(228, 240, 233)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, x, y + 3f * density, paint)
    }

    fun close() {
        closed = true
        tileStore.close()
    }
}
