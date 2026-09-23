package com.example.temiapp



import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Lo mínimo para cargar un modelo .tflite (desde assets/) y ejecutarlo.
 * Pensado para aprender/probar, no para producción (sin GPU, sin gestión de hilos, etc).
 *
 * Uso:
 *   val model = SimpleTFLiteModel(context, "mi_modelo.tflite")
 *   println(model.inputShape().joinToString())   // para ver qué entrada espera
 *   println(model.outputShape().joinToString())  // para ver qué salida da
 *
 *   val input = bitmapToByteBuffer(miBitmap, inputSize = 224)
 *   val output = Array(1) { FloatArray(model.outputShape()[1]) }
 *   model.interpreter.run(input, output)
 *
 *   model.close()
 */
class SimpleTFLiteModel(context: Context, modelAssetName: String) {

    val interpreter: Interpreter = Interpreter(loadModelFile(context, modelAssetName))

    fun inputShape(): IntArray = interpreter.getInputTensor(0).shape()
    fun outputShape(): IntArray = interpreter.getOutputTensor(0).shape()

    fun close() = interpreter.close()

    private fun loadModelFile(context: Context, assetName: String): MappedByteBuffer {
        val afd = context.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).use { input ->
            val channel = input.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }
}

/**
 * Convierte un Bitmap en el ByteBuffer de entrada (float32, normalizado 0-1)
 * que esperan la mayoría de modelos de visión sencillos.
 */
fun bitmapToByteBuffer(bitmap: Bitmap, inputSize: Int): ByteBuffer {
    val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
    buffer.order(ByteOrder.nativeOrder())

    val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
    val pixels = IntArray(inputSize * inputSize)
    resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

    for (pixel in pixels) {
        buffer.putFloat(((pixel shr 16) and 0xFF) / 255f) // R
        buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)  // G
        buffer.putFloat((pixel and 0xFF) / 255f)          // B
    }

    buffer.rewind()
    return buffer
}