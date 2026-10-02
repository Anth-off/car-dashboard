package fr.cockpit.dashboard.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import fr.cockpit.dashboard.navigation.GeoPoint
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Real OSM map tiles with a route overlay, shared by the phone and Android Auto surfaces. */
class RouteMapRenderer(context: Context, private val onInvalidate: () -> Unit) {
    private val density = context.resources.displayMetrics.density.coerceIn(1f, 2f)
    private val tileStore = MapTileStore(context, onInvalidate)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val background = Color.rgb(24, 35, 42)
    private val cyan = Color.rgb(7, 151, 180)
    private var closed = false

    var zoom: Int = 15
        set(value) {
            val next = value.coerceIn(3, 18)
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
    ) {
        if (closed || width <= 0 || height <= 0) return
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(background)
        if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite()
            || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            tileStore.setVisible(emptySet())
            drawCenteredMessage(canvas, width, height, "Position GPS nécessaire")
            drawAttribution(canvas, width, height)
            canvas.restoreToCount(saved)
            return
        }

        val tileCount = 1 shl zoom
        val tileSize = 256.0 * density
        val world = tileCount * tileSize
        val centerX = worldX(longitude, world)
        val centerY = worldY(latitude, world)
        val left = centerX - width / 2.0
        val top = centerY - height / 2.0
        val minX = floor(left / tileSize).toInt()
        val maxX = floor((left + width - 1) / tileSize).toInt()
        val minY = floor(top / tileSize).toInt().coerceAtLeast(0)
        val maxY = floor((top + height - 1) / tileSize).toInt().coerceAtMost(tileCount - 1)
        val visible = buildSet {
            for (y in minY..maxY) for (x in minX..maxX) {
                add(TileKey(zoom, Math.floorMod(x, tileCount), y))
            }
        }
        tileStore.setVisible(visible)
        var drawnTiles = 0
        paint.reset()
        paint.isFilterBitmap = true
        for (y in minY..maxY) for (x in minX..maxX) {
            val bitmap = tileStore.tile(TileKey(zoom, Math.floorMod(x, tileCount), y)) ?: continue
            val xScreen = (x * tileSize - left).toFloat()
            val yScreen = (y * tileSize - top).toFloat()
            canvas.drawBitmap(bitmap, null, RectF(xScreen, yScreen,
                (xScreen + tileSize).toFloat(), (yScreen + tileSize).toFloat()), paint)
            drawnTiles++
        }

        val path = Path()
        var previousX: Double? = null
        var started = false
        routePoints.forEach { point ->
            if (!point.latitude.isFinite() || !point.longitude.isFinite()) return@forEach
            var deltaX = worldX(point.longitude, world) - centerX
            if (deltaX > world / 2) deltaX -= world
            if (deltaX < -world / 2) deltaX += world
            val x = deltaX + width / 2.0
            val y = worldY(point.latitude, world) - top
            if (!started || previousX?.let { kotlin.math.abs(x - it) > world / 2 } == true) {
                path.moveTo(x.toFloat(), y.toFloat())
                started = true
            } else path.lineTo(x.toFloat(), y.toFloat())
            previousX = x
        }
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 8f * density
        paint.color = Color.WHITE
        canvas.drawPath(path, paint)
        paint.strokeWidth = 5f * density
        paint.color = cyan
        canvas.drawPath(path, paint)
        drawPosition(canvas, width / 2f, height / 2f, bearing)

        if (drawnTiles == 0) {
            drawBadge(canvas, width / 2f, height * 0.72f,
                if (tileStore.hasVisibleFailure()) "Carte indisponible · connexion nécessaire" else "Chargement de la carte…",
                12f)
        } else if (tileStore.hasVisibleFailure()) {
            drawBadge(canvas, width / 2f, 25f * density, "Carte partiellement chargée", 11f)
        }
        drawBadge(canvas, width - 22f * density, 24f * density, "N", 13f)
        drawAttribution(canvas, width, height)
        canvas.restoreToCount(saved)
    }

    private fun drawPosition(canvas: Canvas, x: Float, y: Float, bearing: Float?) {
        paint.reset()
        paint.isAntiAlias = true
        paint.color = Color.argb(50, 0, 155, 180)
        canvas.drawCircle(x, y, 24f * density, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, 13f * density, paint)
        paint.color = cyan
        if (bearing != null && bearing.isFinite()) {
            val save = canvas.save()
            canvas.rotate(bearing, x, y)
            val arrow = Path().apply {
                moveTo(x, y - 17f * density)
                lineTo(x + 10f * density, y + 11f * density)
                lineTo(x, y + 6f * density)
                lineTo(x - 10f * density, y + 11f * density)
                close()
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f * density
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.WHITE
            canvas.drawPath(arrow, paint)
            paint.style = Paint.Style.FILL
            paint.color = cyan
            canvas.drawPath(arrow, paint)
            canvas.restoreToCount(save)
        } else canvas.drawCircle(x, y, 9f * density, paint)
    }

    private fun drawCenteredMessage(canvas: Canvas, width: Int, height: Int, text: String) {
        drawBadge(canvas, width / 2f, height / 2f, text, 14f)
    }

    private fun drawAttribution(canvas: Canvas, width: Int, height: Int) {
        val text = "© OpenStreetMap contributors"
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = 10f * density
        paint.textAlign = Paint.Align.LEFT
        val textWidth = paint.measureText(text)
        val left = (width - textWidth - 16f * density).coerceAtLeast(0f)
        paint.color = Color.argb(225, 255, 255, 255)
        canvas.drawRect(left, height - 20f * density, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.rgb(24, 35, 42)
        canvas.drawText(text, left + 7f * density, height - 6f * density, paint)
    }

    private fun drawBadge(canvas: Canvas, x: Float, y: Float, text: String, size: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size * density
        val textWidth = paint.measureText(text)
        val rectangle = RectF(x - textWidth / 2 - 10f * density, y - 15f * density,
            x + textWidth / 2 + 10f * density, y + 11f * density)
        paint.color = Color.argb(235, 24, 35, 42)
        canvas.drawRoundRect(rectangle, 7f * density, 7f * density, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, x, y + 3f * density, paint)
    }

    fun close() {
        closed = true
        tileStore.close()
    }

    private fun worldX(longitude: Double, world: Double): Double = (longitude + 180.0) / 360.0 * world

    private fun worldY(latitude: Double, world: Double): Double {
        val radians = latitude.coerceIn(-85.05112878, 85.05112878) * PI / 180
        return (1.0 - ln(tan(PI / 4 + radians / 2)) / PI) / 2.0 * world
    }
}
