package com.example.temiapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState // NUEVO IMPORT
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.temi.face.TemiFaceManager
import com.example.temiapp.ui.theme.TEMIAppTheme
import com.robotemi.sdk.Robot
import com.robotemi.sdk.face.ContactModel
import com.robotemi.sdk.permission.OnRequestPermissionResultListener
import com.robotemi.sdk.permission.Permission
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

enum class Screen { FACE, CAMERA, MAP }

class MainActivity : ComponentActivity() {

    private val robot = Robot.getInstance()
    private var mapPermissionGranted by mutableStateOf(false)

    private lateinit var faceManager: TemiFaceManager
    private var faceRecognitionActive by mutableStateOf(false)
    private var lastRecognizedFacesText by mutableStateOf("")

    private val mapPermissionListener =
        object : OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(
                permission: Permission,
                grantResult: Int,
                requestCode: Int
            ) {
                if (permission == Permission.MAP && grantResult == Permission.GRANTED) {
                    mapPermissionGranted = true
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        robot.addOnRequestPermissionResultListener(mapPermissionListener)
        PoseTracker.start()

        faceManager = TemiFaceManager(this, packageName)
        faceManager.callback = object : TemiFaceManager.Callback {
            override fun onPermissionResult(granted: Boolean) {
                if (granted) {
                    faceManager.startRecognition(withSdkFaces = true)
                    faceRecognitionActive = true
                } else {
                    Log.e("TEMI_FACE", "Permiso de reconocimiento facial denegado")
                }
            }

            override fun onFaceRecognized(faces: List<ContactModel>) {
                val newText = formatFaces(faces)
                if (lastRecognizedFacesText != newText) {
                    lastRecognizedFacesText = newText
                }
            }

            override fun onContinuousFaceRecognized(faces: List<ContactModel>) {
                val newText = formatFaces(faces)
                if (lastRecognizedFacesText != newText) {
                    lastRecognizedFacesText = newText
                }
            }
        }

        enableEdgeToEdge()

        setContent {
            TEMIAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    FaceScreen(
                        modifier = Modifier.padding(innerPadding),
                        robot = robot,
                        mapPermissionGranted = mapPermissionGranted,
                        faceRecognitionActive = faceRecognitionActive,
                        lastRecognizedFacesText = lastRecognizedFacesText,
                        onToggleFaceRecognition = { toggleFaceRecognition() },
                        onTakePhotosRequested = {
                            faceManager.stopRecognition()
                        },
                        onRegisterFace = { uri, name ->
                            registerNewPerson(uri, name)
                        },
                        onQueryRegisteredFaces = {
                            faceManager.queryRegisteredFaces()
                        },
                        onDeleteFaceByUid = { uid ->
                            deleteFaceByUid(uid)
                        },
                        onDeleteAllFaces = {
                            deleteAllFaces()
                        }
                    )
                }
            }
        }
    }

    private fun registerNewPerson(uri: Uri, name: String) {
        lifecycleScope.launch {
            val uid = UUID.randomUUID().toString()
            val success = faceManager.registerFace(uid, name, uri, grantUriPermission = true)
            if (success) {
                delay(500)
                if (faceRecognitionActive) {
                    faceManager.startRecognition(withSdkFaces = true)
                }
            }
        }
    }

    private fun deleteFaceByUid(uid: String) {
        lifecycleScope.launch {
            val isRunning = faceRecognitionActive
            if (isRunning) faceManager.stopRecognition()

            faceManager.deleteFacesByUid(uid)
            delay(300)

            if (isRunning) faceManager.startRecognition(withSdkFaces = true)
        }
    }

    private fun deleteAllFaces() {
        lifecycleScope.launch {
            val isRunning = faceRecognitionActive
            if (isRunning) faceManager.stopRecognition()

            faceManager.deleteAllFaces()
            delay(300)

            if (isRunning) faceManager.startRecognition(withSdkFaces = true)
        }
    }

    private fun toggleFaceRecognition() {
        if (faceRecognitionActive) {
            faceManager.stopRecognition()
            faceRecognitionActive = false
            lastRecognizedFacesText = ""
        } else {
            faceManager.ensurePermission()
        }
    }

    private fun formatFaces(faces: List<ContactModel>): String {
        if (faces.isEmpty()) return ""
        val intro = if (faces.size > 1) "Múltiples rostros (${faces.size}):\n" else "Rostro detectado:\n"

        return intro + faces.joinToString("\n") { face ->
            val name = if (face.firstName.isBlank()) "Persona desconocida" else "${face.firstName} ${face.lastName}".trim()
            "• $name (Similitud: ${face.similarity})"
        }
    }

    override fun onDestroy() {
        robot.removeOnRequestPermissionResultListener(mapPermissionListener)
        faceManager.release()
        PoseTracker.stop()
        super.onDestroy()
    }
}

fun createImageUri(context: Context): Uri? {
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "temi_face_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
    }
    return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
}

@Composable
fun FaceScreen(
    modifier: Modifier = Modifier,
    robot: Robot,
    mapPermissionGranted: Boolean,
    faceRecognitionActive: Boolean,
    lastRecognizedFacesText: String,
    onToggleFaceRecognition: () -> Unit,
    onTakePhotosRequested: () -> Unit,
    onRegisterFace: (Uri, String) -> Unit,
    onQueryRegisteredFaces: suspend () -> List<TemiFaceManager.RegisteredFace>,
    onDeleteFaceByUid: (String) -> Unit,
    onDeleteAllFaces: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf(Screen.FACE) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) currentScreen = Screen.CAMERA
    }

    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var showNameDialog by remember { mutableStateOf(false) }
    var newPersonName by remember { mutableStateOf("") }

    var showManageFacesDialog by remember { mutableStateOf(false) }
    var showPositionDialog by remember { mutableStateOf(false) } // ESTADO NUEVO PARA EL POPUP

    val registeredFacesList = remember { mutableStateListOf<TemiFaceManager.RegisteredFace>() }

    // Obtenemos la posición en tiempo real desde el StateFlow del PoseTracker
    val currentPosition by PoseTracker.latestPosition.collectAsState()

    fun refreshFacesList() {
        coroutineScope.launch {
            val faces = onQueryRegisteredFaces()
            registeredFacesList.clear()
            registeredFacesList.addAll(faces)
            showManageFacesDialog = true
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoUri != null) {
            showNameDialog = true
        } else {
            onRegisterFace(Uri.EMPTY, "")
        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Registrar nueva persona") },
            text = {
                OutlinedTextField(
                    value = newPersonName,
                    onValueChange = { newPersonName = it },
                    label = { Text("Nombre de la persona") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    photoUri?.let { uri ->
                        onRegisterFace(uri, newPersonName)
                    }
                    showNameDialog = false
                    newPersonName = ""
                }) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                Button(onClick = {
                    showNameDialog = false
                    newPersonName = ""
                }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // DIÁLOGO DE POSICIÓN AÑADIDO
    if (showPositionDialog) {
        AlertDialog(
            onDismissRequest = { showPositionDialog = false },
            title = { Text("Posición actual de Temi") },
            text = {
                Column {
                    Text("Eje X: ${currentPosition.x}")
                    Text("Eje Y: ${currentPosition.y}")
                    Text("Yaw: ${currentPosition.yaw}")
                    Text("Tilt Angle: ${currentPosition.tiltAngle}")
                }
            },
            confirmButton = {
                TextButton(onClick = { showPositionDialog = false }) {
                    Text("Cerrar")
                }
            }
        )
    }

    if (showManageFacesDialog) {
        AlertDialog(
            onDismissRequest = { showManageFacesDialog = false },
            title = { Text("Caras registradas (${registeredFacesList.size})") },
            text = {
                if (registeredFacesList.isEmpty()) {
                    Text("No hay caras guardadas en el sistema.")
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        items(registeredFacesList, key = { it.uid }) { face ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (face.username.isBlank()) "Sin nombre" else face.username,
                                        color = Color.Unspecified
                                    )
                                }
                                IconButton(onClick = {
                                    onDeleteFaceByUid(face.uid)
                                    registeredFacesList.remove(face)
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Eliminar persona",
                                        tint = Color.Red
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageFacesDialog = false }) {
                    Text("Cerrar")
                }
            },
            dismissButton = {
                if (registeredFacesList.isNotEmpty()) {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        onClick = {
                            onDeleteAllFaces()
                            registeredFacesList.clear()
                        }
                    ) {
                        Text("Borrar todas", color = Color.White)
                    }
                }
            }
        )
    }

    LaunchedEffect(mapPermissionGranted) {
        if (mapPermissionGranted) currentScreen = Screen.MAP
    }

    Box(modifier = modifier.fillMaxSize()) {

        when {
            currentScreen == Screen.CAMERA && hasCameraPermission -> {
                CameraPreviewScreen(modifier = Modifier.fillMaxSize())
            }
            currentScreen == Screen.MAP -> {
                MapScreen(modifier = Modifier.fillMaxSize(), robot = robot)
            }
            else -> {
                AndroidView(
                    factory = { ctx -> FaceView(ctx) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            if (faceRecognitionActive) {
                val displayText = if (lastRecognizedFacesText.isNotBlank()) lastRecognizedFacesText else "Buscando cara..."
                Text(
                    text = displayText,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(onClick = {
                    onTakePhotosRequested()
                    photoUri = createImageUri(context)
                    photoUri?.let { takePictureLauncher.launch(it) }
                }) {
                    Text("Nueva persona (Sacar foto)")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(onClick = {
                    refreshFacesList()
                }) {
                    Text("Gestionar / Borrar caras")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(onClick = {
                    onToggleFaceRecognition()
                    currentScreen = Screen.FACE
                }) {
                    Text("Volver a la página principal")
                }

            } else {
                Button(onClick = {
                    onToggleFaceRecognition()
                }) {
                    Text("Iniciar reconocimiento facial")
                }

                Button(onClick = {
                    refreshFacesList()
                }) {
                    Text("Gestionar / Borrar caras")
                }

                Button(onClick = {
                    when {
                        currentScreen == Screen.CAMERA -> currentScreen = Screen.FACE
                        hasCameraPermission -> currentScreen = Screen.CAMERA
                        else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }) {
                    Text(if (currentScreen == Screen.CAMERA) "Volver a la cara" else "Ver cámara RGB en directo")
                }

                Button(onClick = {
                    if (currentScreen == Screen.MAP) {
                        currentScreen = Screen.FACE
                        return@Button
                    }
                    val permission = robot.checkSelfPermission(Permission.MAP)
                    if (permission == Permission.GRANTED) {
                        currentScreen = Screen.MAP
                    } else {
                        robot.requestPermissions(listOf(Permission.MAP), 1001)
                    }
                }) {
                    Text(if (currentScreen == Screen.MAP) "Volver a la cara" else "Ver mapa del Temi")
                }

                // BOTÓN NUEVO PARA ABRIR EL DIÁLOGO DE POSICIÓN
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { showPositionDialog = true }) {
                    Text("Ver posición de Temi")
                }
            }
        }
    }
}