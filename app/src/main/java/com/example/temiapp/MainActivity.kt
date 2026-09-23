package com.example.temiapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.collectAsState
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
import com.robotemi.sdk.listeners.OnRobotReadyListener
import com.robotemi.sdk.permission.OnRequestPermissionResultListener
import com.robotemi.sdk.permission.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

enum class Screen { FACE, CAMERA, MAP }

class MainActivity : ComponentActivity(), OnRobotReadyListener {

    private val robot = Robot.getInstance()

    private var mapPermissionGranted by mutableStateOf(false)

    // ---------------------------------------------------------
    // RECONOCIMIENTO FACIAL
    // ---------------------------------------------------------

    private lateinit var faceManager: TemiFaceManager

    private var faceRecognitionActive by mutableStateOf(false)

    private var lastRecognizedFacesText by mutableStateOf("")

    // ---------------------------------------------------------
    // DETECCIÓN DE PERSONA
    // ---------------------------------------------------------

    private lateinit var personDetection: PersonDetection

    private var personDetectionRunning by mutableStateOf(false)

    private var personDetected by mutableStateOf(false)

    private var personAngle by mutableStateOf(0.0)

    private var personDistance by mutableStateOf(0.0)

    // ---------------------------------------------------------
    // PERMISOS DEL MAPA
    // ---------------------------------------------------------

    private val mapPermissionListener =
        object : OnRequestPermissionResultListener {

            override fun onRequestPermissionResult(
                permission: Permission,
                grantResult: Int,
                requestCode: Int
            ) {
                if (
                    permission == Permission.MAP &&
                    grantResult == Permission.GRANTED
                ) {
                    mapPermissionGranted = true
                }
            }
        }

    // =========================================================
    // CICLO DE VIDA ROBOT
    // =========================================================

    override fun onStart() {
        super.onStart()
        robot.addOnRobotReadyListener(this)
    }

    override fun onStop() {
        super.onStop()
        robot.removeOnRobotReadyListener(this)
    }

    override fun onRobotReady(isReady: Boolean) {
        if (isReady) {
            Log.d("TEMI_ROBOT", "El servicio de TEMI está listo para recibir comandos.")
        }
    }

    // =========================================================
    // ON CREATE
    // =========================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // -----------------------------------------------------
        // LISTENER DE PERMISOS DEL MAPA
        // -----------------------------------------------------

        robot.addOnRequestPermissionResultListener(
            mapPermissionListener
        )

        // -----------------------------------------------------
        // POSE TRACKER
        // -----------------------------------------------------

        PoseTracker.start()

        // -----------------------------------------------------
        // GESTOR DE CARAS
        // -----------------------------------------------------

        faceManager = TemiFaceManager(
            this,
            packageName
        )

        faceManager.callback =
            object : TemiFaceManager.Callback {

                override fun onPermissionResult(
                    granted: Boolean
                ) {
                    if (granted) {

                        faceManager.startRecognition(
                            withSdkFaces = true
                        )

                        faceRecognitionActive = true

                    } else {

                        Log.e(
                            "TEMI_FACE",
                            "Permiso de reconocimiento facial denegado"
                        )
                    }
                }

                override fun onFaceRecognized(
                    faces: List<ContactModel>
                ) {

                    val newText = formatFaces(faces)

                    if (
                        lastRecognizedFacesText != newText
                    ) {
                        lastRecognizedFacesText = newText
                    }
                }

                override fun onContinuousFaceRecognized(
                    faces: List<ContactModel>
                ) {

                    val newText = formatFaces(faces)

                    if (
                        lastRecognizedFacesText != newText
                    ) {
                        lastRecognizedFacesText = newText
                    }
                }
            }

        // -----------------------------------------------------
        // DETECCIÓN DE PERSONA
        // -----------------------------------------------------

        personDetection = PersonDetection(robot)

        personDetection.onDataChanged = {

            personDetectionRunning =
                personDetection.isRunning

            personDetected =
                personDetection.isDetected

            personAngle =
                personDetection.angle

            personDistance =
                personDetection.distance
        }

        // -----------------------------------------------------
        // UI
        // -----------------------------------------------------

        enableEdgeToEdge()

        setContent {

            TEMIAppTheme {

                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->

                    FaceScreen(
                        modifier = Modifier.padding(innerPadding),

                        robot = robot,

                        mapPermissionGranted =
                            mapPermissionGranted,

                        faceRecognitionActive =
                            faceRecognitionActive,

                        lastRecognizedFacesText =
                            lastRecognizedFacesText,

                        onToggleFaceRecognition = {
                            toggleFaceRecognition()
                        },

                        onTakePhotosRequested = {
                            faceManager.stopRecognition()
                        },

                        onRegisterFace = { uri, name ->
                            registerNewPerson(
                                uri,
                                name
                            )
                        },

                        onQueryRegisteredFaces = {
                            faceManager.queryRegisteredFaces()
                        },

                        onDeleteFaceByUid = { uid ->
                            deleteFaceByUid(uid)
                        },

                        onDeleteAllFaces = {
                            deleteAllFaces()
                        },

                        // -------------------------------------------------
                        // DETECCIÓN DE PERSONA
                        // -------------------------------------------------

                        personDetectionRunning =
                            personDetectionRunning,

                        personDetected =
                            personDetected,

                        personAngle =
                            personAngle,

                        personDistance =
                            personDistance,

                        onTogglePersonDetection = {

                            if (
                                personDetectionRunning
                            ) {
                                personDetection.stop()
                            } else {
                                // CAMBIADO: Se pasa 2.0f en lugar de 0.8f (2 metros de rango)
                                personDetection.start(
                                    2.0f
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    // =========================================================
    // REGISTRAR PERSONA
    // =========================================================

    private fun registerNewPerson(
        uri: Uri,
        name: String
    ) {

        lifecycleScope.launch {

            val uid =
                UUID.randomUUID().toString()

            val success =
                faceManager.registerFace(
                    uid,
                    name,
                    uri,
                    grantUriPermission = true
                )

            if (success) {

                delay(500)

                if (faceRecognitionActive) {

                    faceManager.startRecognition(
                        withSdkFaces = true
                    )
                }
            }
        }
    }

    // =========================================================
    // BORRAR CARA
    // =========================================================

    private fun deleteFaceByUid(
        uid: String
    ) {

        lifecycleScope.launch {

            val isRunning =
                faceRecognitionActive

            if (isRunning) {
                faceManager.stopRecognition()
            }

            faceManager.deleteFacesByUid(uid)

            delay(300)

            if (isRunning) {

                faceManager.startRecognition(
                    withSdkFaces = true
                )
            }
        }
    }

    // =========================================================
    // BORRAR TODAS LAS CARAS
    // =========================================================

    private fun deleteAllFaces() {

        lifecycleScope.launch {

            val isRunning =
                faceRecognitionActive

            if (isRunning) {
                faceManager.stopRecognition()
            }

            faceManager.deleteAllFaces()

            delay(300)

            if (isRunning) {

                faceManager.startRecognition(
                    withSdkFaces = true
                )
            }
        }
    }

    // =========================================================
    // TOGGLE RECONOCIMIENTO FACIAL
    // =========================================================

    private fun toggleFaceRecognition() {

        if (faceRecognitionActive) {

            faceManager.stopRecognition()

            faceRecognitionActive = false

            lastRecognizedFacesText = ""

        } else {

            faceManager.ensurePermission()
        }
    }

    // =========================================================
    // FORMATEAR CARAS
    // =========================================================

    private fun formatFaces(
        faces: List<ContactModel>
    ): String {

        if (faces.isEmpty()) {
            return ""
        }

        val intro =
            if (faces.size > 1) {
                "Múltiples rostros (${faces.size}):\n"
            } else {
                "Rostro detectado:\n"
            }

        return intro +
                faces.joinToString("\n") { face ->

                    val name =
                        if (face.firstName.isBlank()) {
                            "Persona desconocida"
                        } else {
                            "${face.firstName} ${face.lastName}"
                                .trim()
                        }

                    "• $name (Similitud: ${face.similarity})"
                }
    }

    // =========================================================
    // ON DESTROY
    // =========================================================

    override fun onDestroy() {

        robot.removeOnRequestPermissionResultListener(
            mapPermissionListener
        )

        if (::personDetection.isInitialized) {
            personDetection.release()
        }

        faceManager.release()

        PoseTracker.stop()

        super.onDestroy()
    }
}

// =============================================================
// CREAR URI PARA FOTOGRAFÍA
// =============================================================

fun createImageUri(
    context: Context
): Uri? {

    val contentValues =
        ContentValues().apply {

            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "temi_face_${System.currentTimeMillis()}.jpg"
            )

            put(
                MediaStore.Images.Media.MIME_TYPE,
                "image/jpeg"
            )
        }

    return context.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    )
}

// =============================================================
// FACE SCREEN
// =============================================================

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

    onQueryRegisteredFaces:
    suspend () -> List<TemiFaceManager.RegisteredFace>,

    onDeleteFaceByUid: (String) -> Unit,

    onDeleteAllFaces: () -> Unit,

    // ---------------------------------------------------------
    // DETECCIÓN DE PERSONA
    // ---------------------------------------------------------

    personDetectionRunning: Boolean,

    personDetected: Boolean,

    personAngle: Double,

    personDistance: Double,

    onTogglePersonDetection: () -> Unit
) {

    val context = LocalContext.current

    val coroutineScope =
        rememberCoroutineScope()

    var currentScreen by remember {
        mutableStateOf(Screen.FACE)
    }

    // ---------------------------------------------------------
    // CÁMARA
    // ---------------------------------------------------------

    var hasCameraPermission by remember {

        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.RequestPermission()
        ) { granted ->

            hasCameraPermission = granted

            if (granted) {
                currentScreen = Screen.CAMERA
            }
        }

    // ---------------------------------------------------------
    // FOTO
    // ---------------------------------------------------------

    var photoUri by remember {
        mutableStateOf<Uri?>(null)
    }

    var showNameDialog by remember {
        mutableStateOf(false)
    }

    var newPersonName by remember {
        mutableStateOf("")
    }

    // ---------------------------------------------------------
    // DIÁLOGOS
    // ---------------------------------------------------------

    var showManageFacesDialog by remember {
        mutableStateOf(false)
    }

    var showPositionDialog by remember {
        mutableStateOf(false)
    }

    // ---------------------------------------------------------
    // TFLITE
    // ---------------------------------------------------------

    var tfliteResultText by remember {
        mutableStateOf<String?>(null)
    }

    // ---------------------------------------------------------
    // LISTA DE CARAS
    // ---------------------------------------------------------

    val registeredFacesList =
        remember {
            mutableStateListOf<
                    TemiFaceManager.RegisteredFace
                    >()
        }

    // ---------------------------------------------------------
    // POSICIÓN
    // ---------------------------------------------------------

    val currentPosition by
    PoseTracker.latestPosition.collectAsState()

    // =========================================================
    // REFRESCAR CARAS
    // =========================================================

    fun refreshFacesList() {

        coroutineScope.launch {

            val faces =
                onQueryRegisteredFaces()

            registeredFacesList.clear()

            registeredFacesList.addAll(
                faces
            )

            showManageFacesDialog = true
        }
    }

    // =========================================================
    // TFLITE
    // =========================================================

    fun runTFLiteTest() {

        tfliteResultText =
            "Cargando modelo..."

        coroutineScope.launch(Dispatchers.Default) {

            try {

                val model =
                    SimpleTFLiteModel(
                        context,
                        "modelo.tflite"
                    )

                val inputTensor =
                    model.interpreter
                        .getInputTensor(0)

                val outputTensor =
                    model.interpreter
                        .getOutputTensor(0)

                val inputShape =
                    inputTensor.shape()

                val outputShape =
                    outputTensor.shape()

                val inputType =
                    inputTensor.dataType()

                val outputType =
                    outputTensor.dataType()

                Log.d(
                    "TFLITE_TEST",
                    "Entrada: " +
                            inputShape.joinToString() +
                            " tipo=$inputType"
                )

                Log.d(
                    "TFLITE_TEST",
                    "Salida: " +
                            outputShape.joinToString() +
                            " tipo=$outputType"
                )

                // -------------------------------------------------
                // TAMAÑO DE IMAGEN
                // -------------------------------------------------

                val inputHeight =
                    inputShape[1]

                val inputWidth =
                    inputShape[2]

                // -------------------------------------------------
                // IMAGEN DE PRUEBA
                // -------------------------------------------------

                val testBitmap =
                    Bitmap.createBitmap(
                        inputWidth,
                        inputHeight,
                        Bitmap.Config.ARGB_8888
                    ).apply {

                        eraseColor(
                            android.graphics.Color.GRAY
                        )
                    }

                // -------------------------------------------------
                // PREPARAR ENTRADA
                // -------------------------------------------------

                val input: ByteBuffer

                when (inputType) {

                    org.tensorflow.lite.DataType.FLOAT32 -> {

                        input =
                            bitmapToByteBuffer(
                                testBitmap,
                                inputWidth
                            )
                    }

                    org.tensorflow.lite.DataType.UINT8 -> {

                        input =
                            ByteBuffer.allocateDirect(
                                inputWidth *
                                        inputHeight *
                                        3
                            ).order(
                                ByteOrder.nativeOrder()
                            )

                        val pixels =
                            IntArray(
                                inputWidth *
                                        inputHeight
                            )

                        testBitmap.getPixels(
                            pixels,
                            0,
                            inputWidth,
                            0,
                            0,
                            inputWidth,
                            inputHeight
                        )

                        for (pixel in pixels) {

                            input.put(
                                ((pixel shr 16) and 0xFF)
                                    .toByte()
                            )

                            input.put(
                                ((pixel shr 8) and 0xFF)
                                    .toByte()
                            )

                            input.put(
                                (pixel and 0xFF)
                                    .toByte()
                            )
                        }

                        input.rewind()
                    }

                    org.tensorflow.lite.DataType.INT8 -> {

                        input =
                            ByteBuffer.allocateDirect(
                                inputWidth *
                                        inputHeight *
                                        3
                            ).order(
                                ByteOrder.nativeOrder()
                            )

                        val pixels =
                            IntArray(
                                inputWidth *
                                        inputHeight
                            )

                        testBitmap.getPixels(
                            pixels,
                            0,
                            inputWidth,
                            0,
                            0,
                            inputWidth,
                            inputHeight
                        )

                        for (pixel in pixels) {

                            input.put(
                                (
                                        ((pixel shr 16) and 0xFF) -
                                                128
                                        ).toByte()
                            )

                            input.put(
                                (
                                        ((pixel shr 8) and 0xFF) -
                                                128
                                        ).toByte()
                            )

                            input.put(
                                (
                                        (pixel and 0xFF) -
                                                128
                                        ).toByte()
                            )
                        }

                        input.rewind()
                    }

                    else -> {

                        throw IllegalArgumentException(
                            "Tipo de entrada no soportado: $inputType"
                        )
                    }
                }

                // -------------------------------------------------
                // PREPARAR SALIDA
                // -------------------------------------------------

                val numClases =
                    outputShape.last()

                val output =
                    Array(1) {
                        FloatArray(numClases)
                    }

                // -------------------------------------------------
                // EJECUTAR MODELO
                // -------------------------------------------------

                Log.d(
                    "TFLITE_TEST",
                    "Bytes de entrada: " +
                            input.capacity()
                )

                model.interpreter.run(
                    input,
                    output
                )

                // -------------------------------------------------
                // RESULTADO
                // -------------------------------------------------

                val resumen =
                    "Entrada: ${inputShape.joinToString()}\n" +
                            "Tipo entrada: $inputType\n" +
                            "Bytes entrada: ${input.capacity()}\n" +
                            "Salida: ${outputShape.joinToString()}\n" +
                            "Tipo salida: $outputType\n" +
                            "Primeros valores: ${output[0].take(5)}"

                Log.d(
                    "TFLITE_TEST",
                    resumen
                )

                tfliteResultText =
                    resumen

                model.close()

            } catch (e: Exception) {

                Log.e(
                    "TFLITE_TEST",
                    "Error lanzando el modelo",
                    e
                )

                tfliteResultText =
                    "Error: ${e.message}"
            }
        }
    }

    // =========================================================
    // TAKE PICTURE
    // =========================================================

    val takePictureLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.TakePicture()
        ) { success ->

            if (
                success &&
                photoUri != null
            ) {

                showNameDialog = true

            } else {

                onRegisterFace(
                    Uri.EMPTY,
                    ""
                )
            }
        }

    // =========================================================
    // DIÁLOGO: NOMBRE
    // =========================================================

    if (showNameDialog) {

        AlertDialog(

            onDismissRequest = {
                showNameDialog = false
            },

            title = {
                Text(
                    "Registrar nueva persona"
                )
            },

            text = {

                OutlinedTextField(

                    value = newPersonName,

                    onValueChange = {
                        newPersonName = it
                    },

                    label = {
                        Text(
                            "Nombre de la persona"
                        )
                    }
                )
            },

            confirmButton = {

                Button(
                    onClick = {

                        photoUri?.let { uri ->

                            onRegisterFace(
                                uri,
                                newPersonName
                            )
                        }

                        showNameDialog = false

                        newPersonName = ""
                    }
                ) {

                    Text("Guardar")
                }
            },

            dismissButton = {

                Button(
                    onClick = {

                        showNameDialog = false

                        newPersonName = ""
                    }
                ) {

                    Text("Cancelar")
                }
            }
        )
    }

    // =========================================================
    // DIÁLOGO: POSICIÓN
    // =========================================================

    if (showPositionDialog) {

        AlertDialog(

            onDismissRequest = {
                showPositionDialog = false
            },

            title = {
                Text(
                    "Posición actual de Temi"
                )
            },

            text = {

                Column {

                    Text(
                        "Eje X: ${currentPosition.x}"
                    )

                    Text(
                        "Eje Y: ${currentPosition.y}"
                    )

                    Text(
                        "Yaw: ${currentPosition.yaw}"
                    )

                    Text(
                        "Tilt Angle: ${currentPosition.tiltAngle}"
                    )
                }
            },

            confirmButton = {

                TextButton(
                    onClick = {
                        showPositionDialog = false
                    }
                ) {

                    Text("Cerrar")
                }
            }
        )
    }

    // =========================================================
    // DIÁLOGO: CARAS
    // =========================================================

    if (showManageFacesDialog) {

        AlertDialog(

            onDismissRequest = {
                showManageFacesDialog = false
            },

            title = {

                Text(
                    "Caras registradas " +
                            "(${registeredFacesList.size})"
                )
            },

            text = {

                if (registeredFacesList.isEmpty()) {

                    Text(
                        "No hay caras guardadas " +
                                "en el sistema."
                    )

                } else {

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(
                                max = 300.dp
                            )
                    ) {

                        items(
                            registeredFacesList,
                            key = { it.uid }
                        ) { face ->

                            Row(

                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            vertical = 4.dp
                                        ),

                                horizontalArrangement =
                                    Arrangement.SpaceBetween,

                                verticalAlignment =
                                    Alignment.CenterVertically

                            ) {

                                Column(
                                    modifier =
                                        Modifier.weight(1f)
                                ) {

                                    Text(

                                        text =
                                            if (
                                                face.username
                                                    .isBlank()
                                            ) {
                                                "Sin nombre"
                                            } else {
                                                face.username
                                            },

                                        color =
                                            Color.Unspecified
                                    )
                                }

                                IconButton(

                                    onClick = {

                                        onDeleteFaceByUid(
                                            face.uid
                                        )

                                        registeredFacesList
                                            .remove(face)
                                    }

                                ) {

                                    Icon(

                                        imageVector =
                                            Icons.Default.Delete,

                                        contentDescription =
                                            "Eliminar persona",

                                        tint =
                                            Color.Red
                                    )
                                }
                            }

                            HorizontalDivider()
                        }
                    }
                }
            },

            confirmButton = {

                TextButton(
                    onClick = {
                        showManageFacesDialog = false
                    }
                ) {

                    Text("Cerrar")
                }
            },

            dismissButton = {

                if (
                    registeredFacesList.isNotEmpty()
                ) {

                    Button(

                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    Color.Red
                            ),

                        onClick = {

                            onDeleteAllFaces()

                            registeredFacesList.clear()
                        }

                    ) {

                        Text(
                            "Borrar todas",
                            color = Color.White
                        )
                    }
                }
            }
        )
    }

    // =========================================================
    // CAMBIO AUTOMÁTICO AL MAPA
    // =========================================================

    LaunchedEffect(
        mapPermissionGranted
    ) {

        if (mapPermissionGranted) {
            currentScreen = Screen.MAP
        }
    }

    // =========================================================
    // PANTALLA PRINCIPAL
    // =========================================================

    Box(
        modifier =
            modifier.fillMaxSize()
    ) {

        // -----------------------------------------------------
        // CONTENIDO
        // -----------------------------------------------------

        when {

            currentScreen ==
                    Screen.CAMERA &&
                    hasCameraPermission -> {

                CameraPreviewScreen(
                    modifier =
                        Modifier.fillMaxSize()
                )
            }

            currentScreen ==
                    Screen.MAP -> {

                MapScreen(
                    modifier =
                        Modifier.fillMaxSize(),

                    robot = robot
                )
            }

            else -> {

                AndroidView(

                    factory = { ctx ->
                        FaceView(ctx)
                    },

                    modifier =
                        Modifier.fillMaxSize()
                )
            }
        }

        // =====================================================
        // CONTROLES INFERIORES
        // =====================================================

        var showOptions by remember {
            mutableStateOf(false)
        }

        Column(

            modifier =
                Modifier
                    .align(
                        Alignment.BottomCenter
                    )
                    .padding(24.dp),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            // -------------------------------------------------
            // RESULTADO DETECCIÓN DE PERSONA
            // -------------------------------------------------

            if (personDetectionRunning) {

                val detectionText =
                    if (personDetected) {

                        "PERSONA DETECTADA\n" +
                                "Ángulo: %.2f°\n"
                                    .format(personAngle) +
                                "Distancia: %.2f m"
                                    .format(personDistance)

                    } else {

                        "NO HAY PERSONA DETECTADA"
                    }

                Text(

                    text =
                        detectionText,

                    color =
                        Color.White,

                    modifier =
                        Modifier
                            .background(
                                Color.Black.copy(
                                    alpha = 0.7f
                                )
                            )
                            .padding(16.dp)
                )

                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )
            }

            // -------------------------------------------------
            // SUPER BOTÓN
            // -------------------------------------------------

            Button(

                onClick = {
                    showOptions =
                        !showOptions
                }

            ) {

                Text(

                    if (showOptions) {
                        "Ocultar opciones"
                    } else {
                        "Ver opciones"
                    }
                )
            }

            // -------------------------------------------------
            // OPCIONES
            // -------------------------------------------------

            AnimatedVisibility(

                visible =
                    showOptions,

                enter =
                    fadeIn() +
                            expandHorizontally(),

                exit =
                    fadeOut() +
                            shrinkHorizontally()
            ) {

                Row(

                    modifier =
                        Modifier
                            .padding(
                                top = 12.dp
                            )
                            .horizontalScroll(
                                rememberScrollState()
                            ),

                    horizontalArrangement =
                        Arrangement.spacedBy(
                            8.dp
                        ),

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    // -----------------------------------------
                    // RECONOCIMIENTO FACIAL
                    // -----------------------------------------

                    Button(

                        onClick = {
                            onToggleFaceRecognition()
                        }

                    ) {

                        Text(

                            if (
                                faceRecognitionActive
                            ) {
                                "Parar caras"
                            } else {
                                "Iniciar caras"
                            }
                        )
                    }

                    // -----------------------------------------
                    // GESTIONAR CARAS
                    // -----------------------------------------

                    Button(

                        onClick = {
                            refreshFacesList()
                        }

                    ) {

                        Text(
                            "Gestionar caras"
                        )
                    }

                    // -----------------------------------------
                    // CÁMARA RGB
                    // -----------------------------------------

                    Button(

                        onClick = {

                            when {

                                currentScreen ==
                                        Screen.CAMERA -> {

                                    currentScreen =
                                        Screen.FACE
                                }

                                hasCameraPermission -> {

                                    currentScreen =
                                        Screen.CAMERA
                                }

                                else -> {

                                    cameraPermissionLauncher
                                        .launch(
                                            Manifest.permission.CAMERA
                                        )
                                }
                            }
                        }

                    ) {

                        Text(

                            if (
                                currentScreen ==
                                Screen.CAMERA
                            ) {
                                "Volver"
                            } else {
                                "Cámara RGB"
                            }
                        )
                    }

                    // -----------------------------------------
                    // MAPA
                    // -----------------------------------------

                    Button(

                        onClick = {

                            if (
                                currentScreen ==
                                Screen.MAP
                            ) {

                                currentScreen =
                                    Screen.FACE

                                return@Button
                            }

                            val permission =
                                robot.checkSelfPermission(
                                    Permission.MAP
                                )

                            if (
                                permission ==
                                Permission.GRANTED
                            ) {

                                currentScreen =
                                    Screen.MAP

                            } else {

                                robot.requestPermissions(
                                    listOf(
                                        Permission.MAP
                                    ),
                                    1001
                                )
                            }
                        }

                    ) {

                        Text(

                            if (
                                currentScreen ==
                                Screen.MAP
                            ) {
                                "Volver"
                            } else {
                                "Mapa"
                            }
                        )
                    }

                    // -----------------------------------------
                    // POSICIÓN
                    // -----------------------------------------

                    Button(

                        onClick = {
                            showPositionDialog =
                                true
                        }

                    ) {

                        Text(
                            "Posición"
                        )
                    }

                    // -----------------------------------------
                    // TFLITE
                    // -----------------------------------------

                    Button(

                        onClick = {
                            runTFLiteTest()
                        }

                    ) {

                        Text(
                            "Probar TFLite"
                        )
                    }

                    // -----------------------------------------
                    // DETECCIÓN DE PERSONA
                    // -----------------------------------------

                    Button(

                        onClick = {
                            onTogglePersonDetection()
                        }

                    ) {

                        Text(

                            if (
                                personDetectionRunning
                            ) {
                                "Parar detección"
                            } else {
                                "Detectar persona"
                            }
                        )
                    }
                }
            }

            // =================================================
            // RESULTADO TFLITE
            // =================================================

            if (!personDetectionRunning) {

                tfliteResultText?.let {

                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )

                    Text(

                        text = it,

                        color =
                            Color.White,

                        modifier =
                            Modifier
                                .background(
                                    Color.Black.copy(
                                        alpha = 0.6f
                                    )
                                )
                                .padding(8.dp)
                    )
                }
            }
        }
    }
}