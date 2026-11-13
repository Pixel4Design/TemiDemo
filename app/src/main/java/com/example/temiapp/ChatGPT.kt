package com.example.temiapp

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Módulo básico de pruebas: envía texto a ChatGPT, recibe la respuesta
 * y la convierte a voz con ElevenLabs para que Temi la reproduzca.
 *
 * Uso típico desde un Composable o Activity:
 *
 *   val assistant = TemiVoiceAssistant(context)
 *   assistant.ask("Hola, ¿quién eres?", listener = object : TemiVoiceAssistant.Listener {
 *       override fun onReply(text: String) { /* actualizar UI */ }
 *       override fun onSpeakingStarted() { /* opcional */ }
 *       override fun onSpeakingFinished() { /* opcional */ }
 *       override fun onError(message: String) { /* mostrar error */ }
 *   })
 */
class TemiVoiceAssistant(private val context: Context) {

    interface Listener {
        fun onReply(text: String)
        fun onSpeakingStarted() {}
        fun onSpeakingFinished() {}
        fun onError(message: String)
    }

    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Historial de conversación (memoria básica). Reinicia con resetConversation().
    private val conversation = mutableListOf(
        JSONObject().apply {
            put("role", "system")
            put("content", "Eres Temi, un asistente robótico amable. Responde de forma breve y clara.")
        }
    )

    /** Borra el historial y empieza una conversación nueva. */
    fun resetConversation() {
        conversation.clear()
        conversation.add(
            JSONObject().apply {
                put("role", "system")
                put("content", "Eres Temi, un asistente robótico amable. Responde de forma breve y clara.")
            }
        )
    }

    /**
     * Manda [userText] a ChatGPT y, si tiene éxito, convierte la respuesta a voz
     * con ElevenLabs y la reproduce automáticamente.
     */
    fun ask(userText: String, voiceId: String = DEFAULT_VOICE_ID, listener: Listener) {
        if (userText.isBlank()) return

        conversation.add(JSONObject().apply {
            put("role", "user")
            put("content", userText)
        })

        val messagesArray = JSONArray()
        conversation.forEach { messagesArray.put(it) }

        val requestBodyJson = JSONObject().apply {
            put("model", "gpt-3.5-turbo")
            put("temperature", 0.8)
            put("messages", messagesArray)
        }

        val request = Request.Builder()
            .url(OPENAI_CHAT_URL)
            .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
            .header("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyError(listener, "Error de red con ChatGPT: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    notifyError(listener, "HTTP ${response.code} ChatGPT: $body")
                    return
                }
                try {
                    val reply = JSONObject(body)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()

                    conversation.add(JSONObject().apply {
                        put("role", "assistant")
                        put("content", reply)
                    })

                    mainHandler.post { listener.onReply(reply) }

                    textToSpeech(reply, voiceId, listener)
                } catch (e: Exception) {
                    notifyError(listener, "Error parseando respuesta de ChatGPT: ${e.message}")
                }
            }
        })
    }

    /**
     * Convierte [text] a audio con ElevenLabs y lo reproduce.
     * Se puede llamar de forma independiente si ya tienes el texto (p. ej. desde otra fuente).
     */
    fun textToSpeech(text: String, voiceId: String = DEFAULT_VOICE_ID, listener: Listener) {
        if (text.isBlank()) return

        val requestBodyJson = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_multilingual_v2")
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.5)
            })
        }

        val request = Request.Builder()
            .url(ELEVENLABS_TTS_URL + voiceId)
            .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
            .header("xi-api-key", BuildConfig.ELEVENLABS_API_KEY)
            .header("Accept", "audio/mpeg")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyError(listener, "Error de red con ElevenLabs: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    notifyError(listener, "HTTP ${response.code} ElevenLabs: $errorBody")
                    return
                }
                try {
                    val audioBytes = response.body!!.bytes()
                    val audioFile = saveTempAudio(audioBytes)
                    mainHandler.post { playAudio(audioFile, listener) }
                } catch (e: Exception) {
                    notifyError(listener, "Error guardando/reproduciendo audio: ${e.message}")
                }
            }
        })
    }

    private fun saveTempAudio(bytes: ByteArray): File {
        val file = File(context.cacheDir, "temi_tts_${System.currentTimeMillis()}.mp3")
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    private fun playAudio(file: File, listener: Listener) {
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.setOnPreparedListener {
                listener.onSpeakingStarted()
                it.start()
            }
            mediaPlayer.setOnCompletionListener {
                listener.onSpeakingFinished()
                it.release()
                file.delete()
            }
            mediaPlayer.setOnErrorListener { mp, _, _ ->
                notifyError(listener, "Error reproduciendo audio")
                mp.release()
                file.delete()
                true
            }
            mediaPlayer.prepareAsync()
        } catch (e: IOException) {
            notifyError(listener, "No se pudo reproducir el audio: ${e.message}")
        }
    }

    private fun notifyError(listener: Listener, message: String) {
        Log.e("TemiVoiceAssistant", message)
        mainHandler.post { listener.onError(message) }
    }

    companion object {
        private const val OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions"
        private const val ELEVENLABS_TTS_URL = "https://api.elevenlabs.io/v1/text-to-speech/"
        // Voz por defecto de ElevenLabs ("Rachel"); cámbiala por la que prefieras
        private const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"
    }
}