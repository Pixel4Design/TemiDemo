package com.example.temiapp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.robotemi.sdk.face.ContactModel
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.use

// ---- Ajustes ----
private const val REF_WIDTH = 800f          // referencia del faceRect de Temi
private const val REF_HEIGHT = 600f
private const val FACE_MARGIN = 0.2f        // 20 % de margen alrededor de la cara
private const val FACES_MAX_AGE_MS = 1000L  // cuánto tiempo vale la última cara recibida
private const val FACES_MIRRORED = false    // pon true si el recorte sale desplazado en horizontal
private const val MIN_INTERVAL_MS = 150L    // ~6-7 análisis por segundo

// ---- Última lista de caras que ha dado Temi ----
object LatestFaces {
    @Volatile var faces: List<ContactModel> = emptyList()
    @Volatile var timestamp: Long = 0L

    fun update(list: List<ContactModel>) {
        faces = list
        timestamp = SystemClock.elapsedRealtime()
    }
}

class EmotionClassifier(context: Context, modelRes: Int, private val size: Int = 224) {
    // Orden de clases de los modelos de 8 clases (verifícalo en facial_emotions.py)
    private val labels = listOf(
        "Anger", "Contempt", "Disgust", "Fear",
        "Happiness", "Neutral", "Sadness", "Surprise"
    )
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession =
        env.createSession(context.resources.openRawResource(modelRes).readBytes())
    private val inputName: String = session.inputNames.first()

    @Suppress("UNCHECKED_CAST")
    fun classify(face: Bitmap): Pair<String, Float> {
        val bmp = Bitmap.createScaledBitmap(face, size, size, true)
        val pixels = IntArray(size * size)
        bmp.getPixels(pixels, 0, size, 0, 0, size, size)

        // NCHW: primero todo el canal R, luego G, luego B
        val data = FloatArray(3 * size * size)
        val plane = size * size
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            data[i] = (r - mean[0]) / std[0]
            data[plane + i] = (g - mean[1]) / std[1]
            data[2 * plane + i] = (b - mean[2]) / std[2]
        }

        val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val logits = (result[0].value as Array<FloatArray>)[0]
                val max = logits.max()
                val exps = logits.map { exp(it - max) }
                val sum = exps.sum()
                val probs = exps.map { it / sum }
                val best = probs.indices.maxByOrNull { probs[it] }!!
                return labels[best] to probs[best]
            }
        }
    }
}

// ---- Analizador: faceRect -> recorte -> modelo ----
class EmotionFrameAnalyzer(
    private val classifier: EmotionClassifier,
    private val onResult: (String, Bitmap?) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastRun = 0L

    override fun analyze(proxy: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRun < MIN_INTERVAL_MS) return
            lastRun = now

            // 1) Cara más grande entre las últimas que dio Temi (si no son muy antiguas)
            val fresh = now - LatestFaces.timestamp < FACES_MAX_AGE_MS
            val face = if (fresh) {
                LatestFaces.faces
                    .filter { it.faceRect.width() > 0 && it.faceRect.height() > 0 }
                    .maxByOrNull { it.faceRect.width() }
            } else null

            if (face == null) {
                onResult("Sin cara", null)
                return
            }

            // 2) Frame enderezado según la rotación de la cámara
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val upright = if (rot == 0) raw else Bitmap.createBitmap(
                raw, 0, 0, raw.width, raw.height,
                Matrix().apply { postRotate(rot.toFloat()) }, true
            )

            // 3) faceRect (referencia 800x600) -> píxeles del frame
            val rect = face.faceRect
            val sx = upright.width / REF_WIDTH
            val sy = upright.height / REF_HEIGHT
            var left = rect.left * sx
            var right = rect.right * sx
            if (FACES_MIRRORED) {
                val newLeft = upright.width - right
                right = upright.width - left
                left = newLeft
            }
            val top = rect.top * sy
            val bottom = rect.bottom * sy

            // 4) Margen alrededor de la cara y límites de la imagen
            val mx = (right - left) * FACE_MARGIN
            val my = (bottom - top) * FACE_MARGIN
            val l = (left - mx).toInt().coerceIn(0, upright.width - 1)
            val t = (top - my).toInt().coerceIn(0, upright.height - 1)
            val r = (right + mx).toInt().coerceIn(l + 1, upright.width)
            val b = (bottom + my).toInt().coerceIn(t + 1, upright.height)

            if (r - l < 20 || b - t < 20) {
                onResult("Sin cara", null)
                return
            }

            // 5) Recorte -> modelo (no se guarda nada en galería)
            val crop = Bitmap.createBitmap(upright, l, t, r - l, b - t)
            val (emotion, p) = classifier.classify(crop)
            onResult("$emotion ${(p * 100).toInt()}%", crop)
        } finally {
            proxy.close()
        }
    }
}

// ---- Cámara: preview + análisis ----
private fun startEmotionCamera(
    context: android.content.Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    executor: Executor,
    analyzer: EmotionFrameAnalyzer
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val provider = future.get()

        // 4:3 para coincidir con la referencia 800x600 de Temi
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolution)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(executor, analyzer) }

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
        )
    }, ContextCompat.getMainExecutor(context))
}

// ---- Pantalla ----
@Composable
fun EmotionCameraScreen(lifecycleOwner: LifecycleOwner) {
    val context = LocalContext.current
    val classifier = remember { EmotionClassifier(context, R.raw.enet_b0_8_best_afew) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var label by remember { mutableStateOf("Buscando cara...") }
    var crop by remember { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).also { pv ->
                    startEmotionCamera(
                        ctx, lifecycleOwner, pv, executor,
                        EmotionFrameAnalyzer(classifier) { text, bmp ->
                            // El analizador corre en otro hilo: actualizamos la UI en el principal
                            ContextCompat.getMainExecutor(ctx).execute {
                                label = text
                                crop = bmp
                            }
                        }
                    )
                }
            }
        )

        Text(
            text = label,
            color = Color.White,
            fontSize = 36.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 24.dp, vertical = 12.dp)
        )

        // Miniatura de lo que recibe el modelo (útil para depurar el recorte)
        crop?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(140.dp)
            )
        }
    }
}