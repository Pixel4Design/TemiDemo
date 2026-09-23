package com.example.temiapp

import android.os.Handler
import android.os.Looper

import com.robotemi.sdk.Robot
import com.robotemi.sdk.listeners.OnDetectionDataChangedListener
import com.robotemi.sdk.model.DetectionData

class PersonDetection(
    private val robot: Robot
) : OnDetectionDataChangedListener {

    private val handler = Handler(Looper.getMainLooper())

    var isRunning = false
        private set

    var isDetected = false
        private set

    var angle = 0.0
        private set

    var distance = 0.0
        private set

    var onDataChanged: (() -> Unit)? = null

    fun start(maxDistance: Float = 2f) {

        if (isRunning) return

        robot.addOnDetectionDataChangedListener(this)

        robot.setDetectionModeOn(
            true,
            maxDistance
        )

        isRunning = true

        onDataChanged?.invoke()
    }

    fun stop() {

        if (!isRunning) return

        robot.setDetectionModeOn(
            false,
            0.8f
        )

        robot.removeOnDetectionDataChangedListener(this)

        isRunning = false
        isDetected = false
        angle = 0.0
        distance = 0.0

        onDataChanged?.invoke()
    }

    override fun onDetectionDataChanged(
        detectionData: DetectionData
    ) {

        handler.post {

            isDetected = detectionData.isDetected

            angle = detectionData.angle

            distance = detectionData.distance

            onDataChanged?.invoke()
        }
    }

    fun release() {

        stop()

        onDataChanged = null
    }
}