package com.example.temiapp

import com.robotemi.sdk.Robot
import com.robotemi.sdk.navigation.listener.OnCurrentPositionChangedListener
import com.robotemi.sdk.navigation.model.Position
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Mantiene la última posición conocida de Temi.
 *
 * Position contiene:
 *   - x
 *   - y
 *   - yaw
 *   - tiltAngle
 *
 * Los valores se actualizan automáticamente mediante el SDK de Temi.
 */
object PoseTracker : OnCurrentPositionChangedListener {

    private val _latestPosition = MutableStateFlow(Position())

    /**
     * Última posición conocida de Temi.
     */
    val latestPosition: StateFlow<Position> = _latestPosition

    /**
     * Momento en el que recibimos la última actualización real
     * de posición desde Temi.
     */
    private var lastUpdateMs: Long = 0L

    private var started = false

    /**
     * Comienza a escuchar las actualizaciones de posición de Temi.
     */
    fun start() {
        if (started) return

        started = true

        Robot.getInstance()
            .addOnCurrentPositionChangedListener(this)
    }

    /**
     * Deja de escuchar las actualizaciones de posición.
     */
    fun stop() {
        if (!started) return

        started = false

        Robot.getInstance()
            .removeOnCurrentPositionChangedListener(this)
    }

    /**
     * Callback del SDK de Temi.
     *
     * Se ejecuta cada vez que Temi informa de una nueva posición.
     */
    override fun onCurrentPositionChanged(position: Position) {
        _latestPosition.value = position
        lastUpdateMs = System.currentTimeMillis()
    }

    /**
     * Devuelve la última posición conocida.
     */
    fun getLatestPosition(): Position {
        return _latestPosition.value
    }

    /**
     * Milisegundos transcurridos desde la última actualización
     * recibida del robot.
     */
    fun ageMs(): Long {
        return if (lastUpdateMs == 0L) {
            Long.MAX_VALUE
        } else {
            System.currentTimeMillis() - lastUpdateMs
        }
    }

    /**
     * Indica si alguna posición ha sido recibida desde que
     * se inició el tracker.
     */
    fun hasPosition(): Boolean {
        return lastUpdateMs != 0L
    }

    /**
     * Indica si la posición recibida es relativamente reciente.
     *
     * @param maxAgeMs edad máxima permitida en milisegundos.
     */
    fun hasFreshPosition(maxAgeMs: Long = 2000L): Boolean {
        return hasPosition() && ageMs() <= maxAgeMs
    }
}