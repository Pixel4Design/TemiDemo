package com.example.temiapp

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.robotemi.sdk.Robot
import com.robotemi.sdk.map.MapDataModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Pantalla que pide el mapa a Temi (getMapData()) y lo pinta.
 *
 * La conversión de la rejilla a Bitmap sigue el mismo criterio que la app de
 * ejemplo oficial del SDK: cada celda trae un valor 0-100 (probabilidad de
 * ocupación) que se usa como canal ALFA sobre negro puro. Es decir:
 *   - valor alto  -> negro opaco  -> pared / obstáculo
 *   - valor bajo  -> casi transparente -> espacio libre
 * Por eso conviene ponerlo sobre un fondo claro (el Box de abajo ya lo hace).
 */
@Composable
fun MapScreen(modifier: Modifier = Modifier, robot: Robot) {
    var mapDataModel by remember { mutableStateOf<MapDataModel?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadMap() {
        isLoading = true
        errorMsg = null
        scope.launch(Dispatchers.IO) {
            try {
                val data = robot.getMapData() //obtiene los datos del mapa de Temi
                if (data == null) {
                    withContext(Dispatchers.Main) {
                        errorMsg = "No hay mapa disponible todavía.\n¿Ha terminado Temi de mapear el espacio?"
                        isLoading = false
                    }
                    return@launch
                }
                val img = data.mapImage
                val bmp = Bitmap.createBitmap(
                    img.data.map { Color.argb((it * 2.55).roundToInt(), 0, 0, 0) }.toIntArray(),
                    img.cols,
                    img.rows,
                    Bitmap.Config.ARGB_8888
                )
                withContext(Dispatchers.Main) {
                    mapDataModel = data
                    bitmap = bmp
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMsg = "Error leyendo el mapa: ${e.message}"
                    isLoading = false
                }
            }
        }
    }

    // Al entrar en esta pantalla, pedimos el mapa automáticamente una vez.
    LaunchedEffect(Unit) { loadMap() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFFEDEDED)), // fondo claro para que se vea el "alfa" del bitmap
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        val currentMapData = mapDataModel
        when {
            bmp != null && currentMapData != null -> {
                val robotPosition by PoseTracker.latestPosition.collectAsState()
                MapCanvas(
                    bitmap = bmp,
                    mapData = currentMapData,
                    robotPosition = robotPosition,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )
            }
            isLoading -> CircularProgressIndicator()
            errorMsg != null -> Text(errorMsg!!, modifier = Modifier.padding(24.dp))
            else -> Text("Sin datos de mapa todavía")
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            mapDataModel?.let { data ->
                val name = data.mapName.ifBlank { data.mapId }
                Text("Mapa: $name")
                Text("${data.mapImage.cols} x ${data.mapImage.rows} px · resolución ${data.mapInfo.resolution} m/px")
            }
            Button(onClick = { loadMap() }) {
                Text("Actualizar mapa")
            }
        }
    }
}