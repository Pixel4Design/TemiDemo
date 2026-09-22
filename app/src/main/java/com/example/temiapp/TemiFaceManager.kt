package com.example.temi.face

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.face.ContactModel
import com.robotemi.sdk.face.OnContinuousFaceRecognizedListener
import com.robotemi.sdk.face.OnFaceRecognizedListener
import com.robotemi.sdk.permission.OnRequestPermissionResultListener
import com.robotemi.sdk.permission.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TemiFaceManager(
    private val context: Context,
    private val packageName: String,
    private val permissionRequestCode: Int = REQUEST_CODE_FACE_PERMISSION
) {

    companion object {
        private const val TAG = "TemiFaceManager"
        const val REQUEST_CODE_FACE_PERMISSION = 1001

        private val FACE_CONTENT_URI: Uri =
            Uri.parse("content://com.robotemi.sdk.TemiSdkDocumentContentProvider/face")

        private const val COL_UID = "uid"
        private const val COL_USERNAME = "username"
        private const val COL_URI = "uri"
    }

    interface Callback {
        fun onPermissionResult(granted: Boolean) {}
        fun onFaceRecognized(faces: List<ContactModel>) {}
        fun onContinuousFaceRecognized(faces: List<ContactModel>) {}
    }

    var callback: Callback? = null

    private val robot get() = Robot.getInstance()
    private var recognitionActive = false

    private val onFaceRecognizedListener = object : OnFaceRecognizedListener {
        override fun onFaceRecognized(contactModelList: List<ContactModel>) {
            Log.d(TAG, "onFaceRecognized() recibido, ${contactModelList.size} cara(s)")
            callback?.onFaceRecognized(contactModelList)
        }
    }

    private val onContinuousFaceRecognizedListener = object : OnContinuousFaceRecognizedListener {
        override fun onContinuousFaceRecognized(contactModelList: List<ContactModel>) {
            // Este evento se dispara múltiples veces por segundo
            callback?.onContinuousFaceRecognized(contactModelList)
        }
    }

    private val onRequestPermissionResultListener = object : OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(
            permission: Permission,
            grantResult: Int,
            requestCode: Int
        ) {
            if (permission == Permission.FACE_RECOGNITION && requestCode == permissionRequestCode) {
                callback?.onPermissionResult(grantResult == Permission.GRANTED)
            }
        }
    }

    init {
        robot.addOnFaceRecognizedListener(onFaceRecognizedListener)
        robot.addOnContinuousFaceRecognizedListener(onContinuousFaceRecognizedListener)
        robot.addOnRequestPermissionResultListener(onRequestPermissionResultListener)
    }

    fun release() {
        stopRecognition()
        robot.removeOnFaceRecognizedListener(onFaceRecognizedListener)
        robot.removeOnContinuousFaceRecognizedListener(onContinuousFaceRecognizedListener)
        robot.removeOnRequestPermissionResultListener(onRequestPermissionResultListener)
    }

    fun hasPermission(): Boolean {
        val result = robot.checkSelfPermission(Permission.FACE_RECOGNITION)
        return result == Permission.GRANTED
    }

    fun ensurePermission(): Boolean {
        if (hasPermission()) {
            callback?.onPermissionResult(true)
            return true
        }
        robot.requestPermissions(listOf(Permission.FACE_RECOGNITION), permissionRequestCode)
        return false
    }

    fun startRecognition(withSdkFaces: Boolean = true) {
        if (!hasPermission()) return
        robot.startFaceRecognition(withSdkFaces)
        recognitionActive = true
        Log.d(TAG, "robot.startFaceRecognition() invocado")
    }

    fun stopRecognition() {
        if (!recognitionActive) return
        robot.stopFaceRecognition()
        recognitionActive = false
        Log.d(TAG, "robot.stopFaceRecognition() invocado")
    }

    fun isRecognitionActive(): Boolean = recognitionActive

    // Transformada a 'suspend' para no bloquear la interfaz principal
    suspend fun registerFace(
        uid: String,
        username: String,
        imageUri: Uri,
        grantUriPermission: Boolean = imageUri.scheme != "http" && imageUri.scheme != "https"
    ): Boolean = withContext(Dispatchers.IO) {
        if (grantUriPermission) {
            val temiPackages = listOf("com.robotemi.sdk", "com.robotemi.face")
            for (pkg in temiPackages) {
                try {
                    context.grantUriPermission(pkg, imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo conceder permiso URI a $pkg: ${e.message}")
                }
            }
        }

        val values = ContentValues().apply {
            put(COL_UID, uid)
            put(COL_USERNAME, username)
            put(COL_URI, imageUri.toString())
        }

        return@withContext try {
            val result = context.contentResolver.insert(FACE_CONTENT_URI, values)
            Log.d(TAG, "Cara registrada correctamente en ContentProvider: $result")
            result != null
        } catch (e: Exception) {
            Log.e(TAG, "registerFace() falló", e)
            false
        }
    }

    data class RegisteredFace(val uid: String, val username: String)

    // Transformada a 'suspend' para búsquedas asíncronas
    suspend fun queryRegisteredFaces(
        selection: String? = null,
        selectionArgs: Array<String>? = null
    ): List<RegisteredFace> = withContext(Dispatchers.IO) {
        val result = mutableListOf<RegisteredFace>()
        try {
            val cursor = context.contentResolver.query(
                FACE_CONTENT_URI,
                arrayOf(COL_USERNAME, COL_UID),
                selection,
                selectionArgs,
                null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val username = it.getString(0) ?: ""
                    val uid = it.getString(1) ?: ""
                    result.add(RegisteredFace(uid, username))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryRegisteredFaces() falló", e)
        }
        return@withContext result
    }

    // Transformadas a 'suspend'
    suspend fun deleteFacesByUid(uid: String): Int = withContext(Dispatchers.IO) {
        return@withContext try {
            val deletedRows = context.contentResolver.delete(
                FACE_CONTENT_URI,
                "$COL_UID = ?",
                arrayOf(uid)
            )
            Log.d(TAG, "deleteFacesByUid() $uid eliminó $deletedRows fila(s)")
            deletedRows
        } catch (e: Exception) {
            Log.e(TAG, "deleteFacesByUid() falló para uid: $uid", e)
            0
        }
    }

    suspend fun deleteAllFaces(): Int = withContext(Dispatchers.IO) {
        return@withContext try {
            val deletedRows = context.contentResolver.delete(
                FACE_CONTENT_URI,
                null,
                null
            )
            Log.d(TAG, "deleteAllFaces() eliminó $deletedRows fila(s)")
            deletedRows
        } catch (e: Exception) {
            Log.e(TAG, "deleteAllFaces() falló", e)
            0
        }
    }
}