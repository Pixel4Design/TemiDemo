package com.example.temiapp

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.robotemi.sdk.face.ContactModel
import java.io.OutputStream

private const val FACE_RECT_REFERENCE_WIDTH = 800f
private const val FACE_RECT_REFERENCE_HEIGHT = 600f
private const val TAG = "ImageCaptureUtils"

fun captureSilentSnapshot(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    faces: List<ContactModel>,
    onResult: (Boolean) -> Unit
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()

        // 1. FORZAR ASPECT RATIO 4:3 para coincidir con la referencia (800x600) de Temi
        val imageCapture = ImageCapture.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()

        val selector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, imageCapture)

            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.capacity())
                            buffer.get(bytes)
                            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                            val rotation = image.imageInfo.rotationDegrees.toFloat()
                            if (rotation != 0f) {
                                val matrix = Matrix().apply { postRotate(rotation) }
                                bitmap = Bitmap.createBitmap(
                                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                                )
                            }

                            val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

                            // Dibujamos especificando si la cámara invierte horizontalmente
                            drawFacesOnBitmap(mutableBitmap, faces, isMirrored = false)

                            val filename = "Temi_Face_${System.currentTimeMillis()}.jpg"
                            val contentValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            }

                            val resolver = context.contentResolver
                            val uri = resolver.insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
                            )

                            var success = false
                            if (uri != null) {
                                resolver.openOutputStream(uri)?.use { output: OutputStream ->
                                    mutableBitmap.compress(
                                        Bitmap.CompressFormat.JPEG, 100, output
                                    )
                                    success = true
                                }
                            }

                            image.close()
                            cameraProvider.unbindAll()
                            onResult(success)

                        } catch (e: Exception) {
                            Log.e(TAG, "Error al procesar imagen silenciosa", e)
                            image.close()
                            cameraProvider.unbindAll()
                            onResult(false)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Error al capturar frame", exception)
                        cameraProvider.unbindAll()
                        onResult(false)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error al vincular cámara", e)
            cameraProvider.unbindAll()
            onResult(false)
        }
    }, ContextCompat.getMainExecutor(context))
}

private fun drawFacesOnBitmap(
    bitmap: Bitmap,
    faces: List<ContactModel>,
    isMirrored: Boolean = false // Cambiar a true si el desajuste es horizontal
) {
    val canvas = Canvas(bitmap)

    val scaleX = bitmap.width / FACE_RECT_REFERENCE_WIDTH
    val scaleY = bitmap.height / FACE_RECT_REFERENCE_HEIGHT

    val boxPaint = Paint().apply {
        color = android.graphics.Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    val textPaint = Paint().apply {
        color = android.graphics.Color.RED
        textSize = 70f
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    faces.forEach { face ->
        val rect = face.faceRect
        if (rect.width() == 0 && rect.height() == 0) return@forEach

        // Cálculo de posición considerando escalado y posible inversión horizontal
        val left = if (isMirrored) {
            bitmap.width - (rect.right * scaleX)
        } else {
            rect.left * scaleX
        }

        val right = if (isMirrored) {
            bitmap.width - (rect.left * scaleX)
        } else {
            rect.right * scaleX
        }

        val top = rect.top * scaleY
        val bottom = rect.bottom * scaleY

        val scaledRect = RectF(left, top, right, bottom)

        canvas.drawRect(scaledRect, boxPaint)

        val label = if (face.firstName.isBlank()) "Desconocido" else face.firstName
        canvas.drawText(
            label,
            scaledRect.left,
            (scaledRect.top - 20f).coerceAtLeast(70f),
            textPaint
        )
    }
}