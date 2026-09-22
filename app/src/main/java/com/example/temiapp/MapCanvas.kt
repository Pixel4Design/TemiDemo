package com.example.temiapp

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.robotemi.sdk.map.LOCATION
import com.robotemi.sdk.map.MapDataModel
import com.robotemi.sdk.map.ZONE
import com.robotemi.sdk.navigation.model.Position
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dibuja el mapa de Temi en una sola pasada: la rejilla base (bitmap) más,
 * encima, las capas vectoriales que trae `MapDataModel` (zonas, ubicaciones)
 * y, opcionalmente, la posición en vivo del robot.
 *
 * OJO con la orientación: las rejillas de ocupación estilo Temi/ROS suelen
 * guardar la fila 0 de los datos como la fila de MÁS ABAJO del mundo real,
 * mientras que un Bitmap de Android pinta la fila 0 arriba del todo. Por eso
 * aquí se invierte el eje Y al convertir coordenadas del mundo a píxel
 * (`flipY = true`). Si al probarlo ves que las zonas/ubicaciones salen en el
 * lado contrario de donde deberían (o el marcador del robot no coincide con
 * dónde está realmente), cambia `flipY` a `false` — es la forma más rápida
 * de calibrarlo empíricamente, ya que no hay documentación pública que lo
 * confirme con certeza.
 */
@Composable
fun MapCanvas(
    bitmap: Bitmap,
    mapData: MapDataModel,
    robotPosition: Position?,
    modifier: Modifier = Modifier,
    flipY: Boolean = true
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()
        if (bmpW <= 0f || bmpH <= 0f) return@Canvas

        // Escalado "fit" clásico: el mapa entero cabe centrado, sin recortarse.
        val scale = minOf(size.width / bmpW, size.height / bmpH)
        val drawW = bmpW * scale
        val drawH = bmpH * scale
        val offsetX = (size.width - drawW) / 2f
        val offsetY = (size.height - drawH) / 2f

        drawImage(
            image = imageBitmap,
            dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
            dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt())
        )

        val info = mapData.mapInfo
        if (info.resolution <= 0f) return@Canvas // sin resolución no podemos convertir coordenadas

        fun worldToScreen(x: Float, y: Float): Offset {
            val gridCol = (x - info.originX) / info.resolution
            val gridRowFromOrigin = (y - info.originY) / info.resolution
            val gridRow = if (flipY) bmpH - gridRowFromOrigin else gridRowFromOrigin
            return Offset(
                offsetX + gridCol * scale,
                offsetY + gridRow * scale
            )
        }

        // --- Zonas: polígonos rellenos con su nombre ---
        mapData.zones.forEach { layer ->
            val poses = layer.layerPoses
            if (layer.layerCategory != ZONE || poses == null || poses.size < 3) return@forEach

            val path = Path().apply {
                val first = worldToScreen(poses[0].x, poses[0].y)
                moveTo(first.x, first.y)
                poses.drop(1).forEach { pose ->
                    val p = worldToScreen(pose.x, pose.y)
                    lineTo(p.x, p.y)
                }
                close()
            }
            drawPath(path, color = Color(0x552196F3))
            drawPath(path, color = Color(0xFF1565C0), style = Stroke(width = 3f))

            val zoneName = layer.zoneProperty?.name
            if (zoneName != null) {
                val centroid = poses.fold(Offset.Zero) { acc, pose ->
                    val p = worldToScreen(pose.x, pose.y)
                    Offset(acc.x + p.x, acc.y + p.y)
                } / poses.size.toFloat()
                drawText(
                    textMeasurer = textMeasurer,
                    text = zoneName,
                    topLeft = centroid,
                    style = TextStyle(color = Color(0xFF0D47A1), fontSize = 12.sp)
                )
            }
        }

        // --- Ubicaciones guardadas: un punto + su nombre (layerId) ---
        mapData.locations.forEach { layer ->
            if (layer.layerCategory != LOCATION) return@forEach
            val pose = layer.layerPoses?.firstOrNull() ?: return@forEach
            val p = worldToScreen(pose.x, pose.y)
            drawCircle(color = Color(0xFFFF9800), radius = 10f, center = p)
            drawText(
                textMeasurer = textMeasurer,
                text = layer.layerId,
                topLeft = Offset(p.x + 12f, p.y - 12f),
                style = TextStyle(color = Color(0xFFE65100), fontSize = 12.sp)
            )
        }

        // --- Posición en vivo del robot: punto + flecha de orientación (yaw) ---
        robotPosition?.let { pos ->
            val p = worldToScreen(pos.x, pos.y)
            drawCircle(color = Color(0xFF2E7D32), radius = 12f, center = p)
            val arrowLength = 26f
            // Si el mapa está volteado (flipY), el yaw también se ve reflejado en pantalla.
            val ySign = if (flipY) -1f else 1f
            val end = Offset(
                p.x + arrowLength * cos(pos.yaw),
                p.y + ySign * arrowLength * sin(pos.yaw)
            )
            drawLine(color = Color(0xFF2E7D32), start = p, end = end, strokeWidth = 4f)
        }
    }
}