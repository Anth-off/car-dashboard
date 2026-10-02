package fr.cockpit.dashboard.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Row
import androidx.core.graphics.drawable.IconCompat

/** Two short rows keep app content readable without requesting a scrolling list from the host. */
internal fun compactCarRow(title: String, text: String): Row = Row.Builder()
    .setTitle(title)
    .addText(compactCarText(text))
    .build()

/** External names/instructions must not turn a fixed card into paragraphs. */
internal fun compactCarText(text: String, maxLength: Int = 72): String {
    val singleLine = text.replace(Regex("\\s+"), " ").trim()
    return if (singleLine.length <= maxLength) singleLine else singleLine.take(maxLength - 1).trimEnd() + "…"
}

/** Always keep the same two actions: cycling also preserves the host's refresh contract. */
internal fun carPageActions(previous: () -> Unit, next: () -> Unit): ActionStrip = ActionStrip.Builder()
    .addAction(Action.Builder().setIcon(pageArrow(false)).setOnClickListener(previous).build())
    .addAction(Action.Builder().setIcon(pageArrow(true)).setOnClickListener(next).build())
    .build()

private fun pageArrow(forward: Boolean): CarIcon {
    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    Canvas(bitmap).apply {
        val outside = if (forward) 24f else 40f
        val tip = if (forward) 42f else 22f
        drawLine(outside, 12f, tip, 32f, paint)
        drawLine(tip, 32f, outside, 52f, paint)
    }
    return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
}
