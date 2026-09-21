package com.example.temiapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.temiapp.ui.theme.TEMIAppTheme
import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.permission.OnRequestPermissionResultListener
import com.robotemi.sdk.permission.Permission

//Actualmente puedo ver lo que devuelve la cámara (imagen en vivo) y en principo lo que devuelve el mapa generado por Lidar (ver proyecto TEMI del claude para ver mas info

fun getTemiMap() {

    val robot = Robot.getInstance()

    Thread {

        try {

            Log.d("TEMI_MAP", "================================")
            Log.d("TEMI_MAP", "INICIANDO LECTURA DEL MAPA")
            Log.d("TEMI_MAP", "================================")

            val permission =
                robot.checkSelfPermission(Permission.MAP)

            Log.d(
                "TEMI_MAP",
                "Permiso MAP: $permission"
            )

            if (permission != Permission.GRANTED) {

                Log.e(
                    "TEMI_MAP",
                    "MAP no concedido"
                )

                return@Thread
            }

            Log.d(
                "TEMI_MAP",
                "Permiso MAP concedido"
            )

            val mapData = robot.getMapData()

            Log.d(
                "TEMI_MAP",
                "Resultado getMapData(): $mapData"
            )

            val mapElements = robot.getMapElements()

            Log.d(
                "TEMI_MAP",
                "Resultado getMapElements(): $mapElements"
            )

            val mapImage = robot.getMapImage()

            Log.d(
                "TEMI_MAP",
                "Resultado getMapImage(): $mapImage"
            )

        } catch (e: Exception) {

            Log.e(
                "TEMI_MAP",
                "ERROR obteniendo datos del mapa",
                e
            )
        }

    }.start()
}
class MainActivity : ComponentActivity() {

    private val robot = Robot.getInstance()

    private val mapPermissionListener =
        object : OnRequestPermissionResultListener {

            override fun onRequestPermissionResult(
                permission: Permission,
                grantResult: Int,
                requestCode: Int
            ) {

                Log.d(
                    "TEMI_MAP",
                    "Resultado permiso: $permission, " +
                            "grantResult=$grantResult, " +
                            "requestCode=$requestCode"
                )

                if (permission == Permission.MAP &&
                    grantResult == Permission.GRANTED
                ) {

                    Log.d(
                        "TEMI_MAP",
                        "PERMISO MAP CONCEDIDO"
                    )

                    getTemiMap()

                } else if (permission == Permission.MAP) {

                    Log.e(
                        "TEMI_MAP",
                        "PERMISO MAP DENEGADO"
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        robot.addOnRequestPermissionResultListener(
            mapPermissionListener
        )

        enableEdgeToEdge()

        setContent {
            TEMIAppTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->

                    FaceScreen(
                        modifier = Modifier.padding(innerPadding),
                        robot = robot
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        robot.removeOnRequestPermissionResultListener(
            mapPermissionListener
        )

        super.onDestroy()
    }
}

@Composable
fun FaceScreen(
    modifier: Modifier = Modifier,
    robot: Robot
) {
    val context = LocalContext.current

    // Si ya vemos la cámara o no.
    var showCamera by remember { mutableStateOf(false) }

    // Comprobamos si ya tenemos permiso de cámara concedido.
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    // Lanzador para pedir el permiso de cámara en tiempo real.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) showCamera = true
        // Si el usuario deniega el permiso, simplemente nos quedamos en la cara.
    }

    Box(modifier = modifier.fillMaxSize()) {

        if (showCamera && hasCameraPermission) {

            CameraPreviewScreen(
                modifier = Modifier.fillMaxSize()
            )

        } else {

            AndroidView(
                factory = { ctx -> FaceView(ctx) },
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
        ) {

            // BOTÓN CÁMARA

            Button(
                onClick = {

                    if (showCamera) {

                        showCamera = false

                    } else if (hasCameraPermission) {

                        showCamera = true

                    } else {

                        permissionLauncher.launch(
                            Manifest.permission.CAMERA
                        )
                    }
                }
            ) {

                Text(
                    if (showCamera)
                        "Volver a la cara"
                    else
                        "Ver cámara RGB en directo"
                )
            }

            // BOTÓN MAPA

            Button(
                onClick = {

                    val permission =
                        robot.checkSelfPermission(Permission.MAP)

                    Log.d(
                        "TEMI_MAP",
                        "Estado actual MAP: $permission"
                    )

                    if (permission == Permission.GRANTED) {

                        getTemiMap()

                    } else {

                        Log.d(
                            "TEMI_MAP",
                            "Solicitando permiso MAP..."
                        )

                        robot.requestPermissions(
                            listOf(Permission.MAP),
                            1001
                        )
                    }
                }
            ) {
                Text("Obtener mapa del Temi")
            }
        }
    }
}