package com.universal.sleepapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.universal.sleepapp.engine.EngineConfig
import com.universal.sleepapp.engine.EpochData
import com.universal.sleepapp.engine.SleepEngine
import com.universal.sleepapp.engine.Stage
import com.universal.sleepapp.engine.StreamType
import com.universal.sleepapp.hardware.ConnectionState
import com.universal.sleepapp.hardware.PhoneAccelerometerSource
import com.universal.sleepapp.hardware.PushBridgeConnector
import com.universal.sleepapp.hardware.SamsungHealthConnector
import com.universal.sleepapp.hardware.SpikeFilteringListener
import com.universal.sleepapp.hardware.WearableConnector
import com.universal.sleepapp.hardware.WearableSensorListener
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Growable primitive float buffer; every operation takes the shared lock so snapshots are consistent. */
private class FloatSink(private val lock: Any, initialCapacity: Int) {
    private var data = FloatArray(initialCapacity)
    private var size = 0

    fun add3(x: Float, y: Float, z: Float) {
        synchronized(lock) {
            if (size + 3 > data.size) data = data.copyOf(maxOf(data.size * 2, size + 3))
            data[size++] = x
            data[size++] = y
            data[size++] = z
        }
    }

    /** Returns everything collected so far and empties the buffer in a single atomic step. */
    fun drain(): FloatArray = synchronized(lock) {
        val out = data.copyOf(size)
        size = 0
        out
    }
}

/** Growable primitive double buffer sharing the same lock. */
private class DoubleSink(private val lock: Any, initialCapacity: Int) {
    private var data = DoubleArray(initialCapacity)
    private var size = 0

    fun add(v: Double) {
        synchronized(lock) {
            if (size + 1 > data.size) data = data.copyOf(maxOf(data.size * 2, size + 1))
            data[size++] = v
        }
    }

    fun addAll(values: DoubleArray) {
        synchronized(lock) {
            if (size + values.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + values.size))
            System.arraycopy(values, 0, data, size, values.size)
            size += values.size
        }
    }

    fun drain(): DoubleArray = synchronized(lock) {
        val out = data.copyOf(size)
        size = 0
        out
    }
}

class SleepTrackingService : Service(), WearableSensorListener {

    companion object {
        private const val TAG = "SleepTrackingService"

        const val ACTION_START = "com.universal.sleepapp.action.START"
        const val ACTION_STOP = "com.universal.sleepapp.action.STOP"

        const val EXTRA_SOURCE = "extra_source"
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        const val EXTRA_RESTING_BPM = "extra_resting_bpm"
        const val EXTRA_PIXEL_RIGHT_WRIST_PATCH = "extra_pixel_right_wrist_patch"
        const val EXTRA_USE_PHONE_ACCEL = "extra_use_phone_accel"

        const val SOURCE_SAMSUNG = "SAMSUNG"

        private const val CHANNEL_ID = "sleep_tracking_channel"
        private const val NOTIFICATION_ID = 4101
        private const val EPOCH_MS = 30_000L
        private const val MAX_SESSION_MS = 10L * 60L * 60L * 1000L + 30L * 60L * 1000L
        private const val PREFS = "sleep_tracking_service_prefs"

        /**
         * For push-style sources (Samsung) the transport feeding the data (Wear companion,
         * Health Connect poller, ...) calls ingestBpm / ingestIbi / ingestAccelerometer on this bridge.
         */
        @Volatile
        var activeBridge: PushBridgeConnector? = null
            private set

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startIntent(
            context: Context,
            source: String = SOURCE_SAMSUNG,
            deviceAddress: String? = null,
            restingBpm: Double = 60.0,
            pixelRightWristPatch: Boolean = false,
            usePhoneAccelerometer: Boolean = true
        ): Intent = Intent(context, SleepTrackingService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_SOURCE, source)
            putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            putExtra(EXTRA_RESTING_BPM, restingBpm)
            putExtra(EXTRA_PIXEL_RIGHT_WRIST_PATCH, pixelRightWristPatch)
            putExtra(EXTRA_USE_PHONE_ACCEL, usePhoneAccelerometer)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, SleepTrackingService::class.java).apply { action = ACTION_STOP }
    }

    inner class LocalBinder : Binder() {
        fun getService(): SleepTrackingService = this@SleepTrackingService
    }

    private val binder = LocalBinder()

    // ------------------------------------------------------------------ thread-safe epoch buffers
    private val bufferLock = Any()
    private val currentAccBuffer = FloatSink(bufferLock, 4096)
    private val currentIbiBuffer = DoubleSink(bufferLock, 128)
    private val currentBpmBuffer = DoubleSink(bufferLock, 128)

    // ------------------------------------------------------------------ session state
    private val stateLock = Any()
    private val rawStages = ArrayList<Stage>()
    private var stabilizedStages: List<Stage> = emptyList()

    private var engine: SleepEngine? = null
    private var engineConfig = EngineConfig()
    private var streamOverride = StreamType.AUTO
    private var executor: ScheduledExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var connector: WearableConnector? = null
    private var phoneAccel: PhoneAccelerometerSource? = null
    private var csvWriter: BufferedWriter? = null
    private var csvFile: File? = null

    private var epochIndex = 0
    private var sessionStartElapsed = 0L
    private var sessionStartWall = 0L

    @Volatile
    private var connectionDetail: String = "Starting"

    // ------------------------------------------------------------------ public read API for the UI (bound)
    fun getStabilizedStages(): List<Stage> = synchronized(stateLock) { ArrayList(stabilizedStages) }

    fun getSessionStartWallMs(): Long = sessionStartWall

    fun getConnectionDetail(): String = connectionDetail

    // ------------------------------------------------------------------ Service lifecycle
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            endSession()
            return START_NOT_STICKY
        }
        if (isRunning) return START_STICKY

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val source: String
        val address: String?
        val resting: Double
        val pixelPatch: Boolean
        val phoneAcc: Boolean
        if (intent != null && intent.hasExtra(EXTRA_SOURCE)) {
            source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_SAMSUNG
            address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
            resting = intent.getDoubleExtra(EXTRA_RESTING_BPM, 60.0)
            pixelPatch = intent.getBooleanExtra(EXTRA_PIXEL_RIGHT_WRIST_PATCH, false)
            phoneAcc = intent.getBooleanExtra(EXTRA_USE_PHONE_ACCEL, true)
            prefs.edit()
                .putString(EXTRA_SOURCE, source)
                .putString(EXTRA_DEVICE_ADDRESS, address)
                .putFloat(EXTRA_RESTING_BPM, resting.toFloat())
                .putBoolean(EXTRA_PIXEL_RIGHT_WRIST_PATCH, pixelPatch)
                .putBoolean(EXTRA_USE_PHONE_ACCEL, phoneAcc)
                .apply()
        } else if (prefs.contains(EXTRA_SOURCE)) {
            // Service was restarted by the system after being killed: resume with the last configuration.
            source = prefs.getString(EXTRA_SOURCE, SOURCE_SAMSUNG) ?: SOURCE_SAMSUNG
            address = prefs.getString(EXTRA_DEVICE_ADDRESS, null)
            resting = prefs.getFloat(EXTRA_RESTING_BPM, 60f).toDouble()
            pixelPatch = prefs.getBoolean(EXTRA_PIXEL_RIGHT_WRIST_PATCH, false)
            phoneAcc = prefs.getBoolean(EXTRA_USE_PHONE_ACCEL, true)
        } else {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            enterForeground()
        } catch (e: SecurityException) {
            Log.e(TAG, "Foreground service start rejected: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Foreground service start not allowed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        beginSession(source, address, resting, pixelPatch, phoneAcc)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not end the night: the service keeps running.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ session setup / teardown
    private fun beginSession(
        source: String,
        address: String?,
        restingBpm: Double,
        pixelPatch: Boolean,
        usePhoneAccel: Boolean
    ) {
        isRunning = true
        sessionStartElapsed = SystemClock.elapsedRealtime()
        sessionStartWall = System.currentTimeMillis()
        epochIndex = 0
        synchronized(stateLock) {
            rawStages.clear()
            stabilizedStages = emptyList()
        }
        currentAccBuffer.drain()
        currentIbiBuffer.drain()
        currentBpmBuffer.drain()

        acquireWakeLock()

        engineConfig = EngineConfig(daytimeRestingBpm = restingBpm)
        engine = SleepEngine(engineConfig)
        streamOverride = StreamType.BPM
        openCsv()

        val sink: WearableSensorListener = if (pixelPatch) SpikeFilteringListener(this) else this
        val newConnector: WearableConnector = SamsungHealthConnector()
        activeBridge = newConnector as? PushBridgeConnector
        connector = newConnector
        newConnector.start(sink)

        if (usePhoneAccel) {
            val acc = PhoneAccelerometerSource(applicationContext)
            if (acc.start(this)) {
                phoneAccel = acc
            } else {
                connectionDetail = "Phone accelerometer unavailable"
            }
        }

        val ex = Executors.newSingleThreadScheduledExecutor()
        executor = ex
        ex.scheduleAtFixedRate({ tick() }, EPOCH_MS, EPOCH_MS, TimeUnit.MILLISECONDS)
        updateNotification("Tracking started - waiting for first 30 s epoch")
    }

    private fun endSession() {
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        if (!isRunning && executor == null && connector == null) return
        isRunning = false
        executor?.shutdownNow()
        executor = null
        try {
            connector?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Connector stop failed: ${e.message}")
        }
        connector = null
        activeBridge = null
        try {
            phoneAccel?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Accelerometer stop failed: ${e.message}")
        }
        phoneAccel = null
        writeFinalHypnogram()
        try {
            csvWriter?.close()
        } catch (_: Exception) {
        }
        csvWriter = null
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepApp::SleepTracking")
        lock.setReferenceCounted(false)
        lock.acquire(MAX_SESSION_MS + 30L * 60L * 1000L)
        wakeLock = lock
    }

    // ------------------------------------------------------------------ the 30-second master loop
    private fun tick() {
        try {
            val eng = engine ?: return

            val snapshot = synchronized(bufferLock) {
                Triple(currentAccBuffer.drain(), currentIbiBuffer.drain(), currentBpmBuffer.drain())
            }

            val index = epochIndex++
            val result = eng.processEpoch(
                EpochData(index, snapshot.first, snapshot.second, snapshot.third, streamOverride)
            )

            val smoothed = synchronized(stateLock) {
                rawStages.add(result.stage)
                stabilizedStages = eng.smoothNight()
                stabilizedStages
            }

            appendCsv(index, result.stage, smoothed.lastOrNull(), result.features.route.name,
                result.features.meanBpm, result.features.rmssdMs, result.features.moveFraction)

            val elapsed = SystemClock.elapsedRealtime() - sessionStartElapsed
            updateNotification(buildStatusText(smoothed, elapsed))

            if (elapsed >= MAX_SESSION_MS) {
                Log.i(TAG, "Maximum session length reached, stopping")
                endSession()
            }
        } catch (t: Throwable) {
            // An uncaught exception would cancel all future runs of scheduleAtFixedRate, so it is contained here.
            Log.e(TAG, "Epoch processing failed: ${t.message}", t)
        }
    }

    private fun buildStatusText(smoothed: List<Stage>, elapsedMs: Long): String {
        val current = smoothed.lastOrNull()?.name ?: "-"
        val totalMin = (elapsedMs / 60_000L).toInt()
        val minutes = SleepEngine.summarize(smoothed, engineConfig.epochSeconds)
        val deepMin = (minutes[Stage.DEEP] ?: 0.0).toInt()
        val remMin = (minutes[Stage.REM] ?: 0.0).toInt()
        return "Stage: $current  |  ${totalMin / 60}h ${totalMin % 60}m tracked  |  " +
                "Deep ${deepMin}m  REM ${remMin}m  |  $connectionDetail"
    }

    // ------------------------------------------------------------------ WearableSensorListener (sensor threads)
    override fun onIbiIntervals(timestampMs: Long, ibiMs: DoubleArray) {
        currentIbiBuffer.addAll(ibiMs)
    }

    override fun onHeartRateBpm(timestampMs: Long, bpm: Double) {
        currentBpmBuffer.add(bpm)
    }

    override fun onAccelerometer(timestampMs: Long, x: Float, y: Float, z: Float) {
        currentAccBuffer.add3(x, y, z)
    }

    override fun onConnectionStateChanged(state: ConnectionState, detail: String?) {
        connectionDetail = if (detail.isNullOrBlank()) state.name else detail
    }

    // ------------------------------------------------------------------ notification
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Sleep tracking", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Ongoing overnight sleep tracking"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun enterForeground() {
        createChannel()
        val notification = buildNotification("Starting sleep tracking")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = if (launch != null) {
            PendingIntent.getActivity(
                this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Sleep tracking active")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "Notification update failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ crash-safe session log
    private fun openCsv() {
        try {
            val file = File(filesDir, "sleep_session_$sessionStartWall.csv")
            csvFile = file
            val writer = BufferedWriter(FileWriter(file, true))
            writer.write("epoch,wall_ms,raw_stage,smoothed_latest,route,mean_bpm,rmssd_ms,move_fraction\n")
            writer.flush()
            csvWriter = writer
        } catch (e: Exception) {
            Log.w(TAG, "Could not open session log: ${e.message}")
            csvWriter = null
        }
    }

    private fun appendCsv(
        epoch: Int,
        raw: Stage,
        smoothedLatest: Stage?,
        route: String,
        meanBpm: Double,
        rmssd: Double,
        moveFraction: Double
    ) {
        try {
            val w = csvWriter ?: return
            w.write(
                "$epoch,${System.currentTimeMillis()},${raw.name},${smoothedLatest?.name ?: ""}," +
                        "$route,${fmt(meanBpm)},${fmt(rmssd)},${fmt(moveFraction)}\n"
            )
            w.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Session log write failed: ${e.message}")
        }
    }

    private fun fmt(v: Double): String = if (v.isFinite()) String.format(java.util.Locale.US, "%.3f", v) else ""

    /** Writes the final globally smoothed hypnogram next to the running log. */
    private fun writeFinalHypnogram() {
        try {
            val stages = synchronized(stateLock) { ArrayList(stabilizedStages) }
            if (stages.isEmpty()) return
            val file = File(filesDir, "sleep_session_${sessionStartWall}_hypnogram.csv")
            BufferedWriter(FileWriter(file, false)).use { w ->
                w.write("epoch,start_s,stage\n")
                for ((i, s) in stages.withIndex()) {
                    w.write("$i,${i * engineConfig.epochSeconds},${s.name}\n")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Final hypnogram write failed: ${e.message}")
        }
    }
}