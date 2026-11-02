package com.universal.sleepapp.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlin.math.min

// =====================================================================================
// Unified listener + connector contracts
// =====================================================================================

enum class ConnectionState { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

/**
 * Everything the tracking service needs from any wearable. A source calls whichever methods it can supply:
 * raw IBI arrays (milliseconds) and/or plain BPM doubles, plus optional accelerometer samples.
 */
interface WearableSensorListener {
    /** Raw beat-to-beat intervals in milliseconds, in arrival order. */
    fun onIbiIntervals(timestampMs: Long, ibiMs: DoubleArray)

    /** A single averaged heart rate value in beats per minute. */
    fun onHeartRateBpm(timestampMs: Long, bpm: Double)

    /** One 3-axis accelerometer sample (m/s^2 or g; the engine auto-detects the unit). */
    fun onAccelerometer(timestampMs: Long, x: Float, y: Float, z: Float)

    fun onConnectionStateChanged(state: ConnectionState, detail: String?)
}

interface WearableConnector {
    val displayName: String

    /** True when the device can deliver millisecond-resolution IBI (enables true HRV on the engine). */
    val providesRawIbi: Boolean

    fun start(listener: WearableSensorListener)
    fun stop()
}

// =====================================================================================
// Push-style ingestion bridges (Samsung / Xiaomi / Garmin)
// =====================================================================================

/**
 * Transport-agnostic bridge. The actual transport calls the ingest* methods;
 * the bridge sanitises values and forwards them to the active listener.
 */
abstract class PushBridgeConnector(
    override val displayName: String,
    override val providesRawIbi: Boolean
) : WearableConnector {

    @Volatile
    protected var listener: WearableSensorListener? = null

    override fun start(listener: WearableSensorListener) {
        this.listener = listener
        listener.onConnectionStateChanged(ConnectionState.CONNECTED, "$displayName bridge ready")
    }

    override fun stop() {
        listener?.onConnectionStateChanged(ConnectionState.IDLE, "$displayName bridge stopped")
        listener = null
    }

    /** Vendor specific sanity rules; return false to drop the sample. */
    protected open fun acceptBpm(bpm: Double): Boolean = bpm.isFinite() && bpm > 0.0 && bpm < 250.0

    fun ingestBpm(timestampMs: Long, bpm: Double) {
        if (!acceptBpm(bpm)) return
        listener?.onHeartRateBpm(timestampMs, bpm)
    }

    fun ingestBpmBatch(timestampMs: Long, bpmValues: DoubleArray) {
        for (v in bpmValues) ingestBpm(timestampMs, v)
    }

    open fun ingestIbi(timestampMs: Long, ibiMs: DoubleArray) {
        if (!providesRawIbi || ibiMs.isEmpty()) return
        listener?.onIbiIntervals(timestampMs, ibiMs)
    }

    fun ingestAccelerometer(timestampMs: Long, x: Float, y: Float, z: Float) {
        listener?.onAccelerometer(timestampMs, x, y, z)
    }
}

/** Samsung Health exposes averaged BPM only. */
class SamsungHealthConnector : PushBridgeConnector("Samsung Health", providesRawIbi = false) {
    override fun ingestIbi(timestampMs: Long, ibiMs: DoubleArray) {}
    override fun acceptBpm(bpm: Double): Boolean = bpm.isFinite() && bpm >= 30.0 && bpm <= 220.0
}

/** Xiaomi / Mi Fitness style bands: BPM only, 0 means "no reading". */
class XiaomiConnector : PushBridgeConnector("Xiaomi", providesRawIbi = false) {
    override fun ingestIbi(timestampMs: Long, ibiMs: DoubleArray) {}
    override fun acceptBpm(bpm: Double): Boolean = bpm.isFinite() && bpm >= 30.0 && bpm <= 220.0
}

/** Garmin (Connect IQ / ANT+ bridge). Accepts raw beat-to-beat IBI when available. */
class GarminConnector : PushBridgeConnector("Garmin", providesRawIbi = true) {
    override fun acceptBpm(bpm: Double): Boolean = bpm.isFinite() && bpm >= 30.0 && bpm <= 220.0
}

// =====================================================================================
// Phone accelerometer (actigraphy source for chest straps that carry no motion data)
// =====================================================================================

class PhoneAccelerometerSource(
    context: Context,
    private val samplingPeriodUs: Int = 40_000
) : SensorEventListener {

    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Volatile
    private var listener: WearableSensorListener? = null

    fun start(target: WearableSensorListener): Boolean {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        listener = target
        return sensorManager.registerListener(this, sensor, samplingPeriodUs)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        listener = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (v.size >= 3) {
            listener?.onAccelerometer(SystemClock.elapsedRealtime(), v[0], v[1], v[2])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

// =====================================================================================
// Bluetooth SIG Heart Rate Measurement parser
// =====================================================================================

object HeartRateMeasurementParser {

    class Parsed(
        val bpm: Int,
        val contactDetected: Boolean?,
        val rrMs: DoubleArray
    )

    fun parse(data: ByteArray?): Parsed? {
        if (data == null || data.size < 2) return null
        val flags = data[0].toInt() and 0xFF
        var i = 1
        val bpm: Int
        if ((flags and 0x01) != 0) {
            if (data.size < i + 2) return null
            bpm = u16(data, i)
            i += 2
        } else {
            bpm = data[i].toInt() and 0xFF
            i += 1
        }
        val contactBits = (flags shr 1) and 0x03
        val contact: Boolean? = when (contactBits) {
            2 -> false
            3 -> true
            else -> null
        }
        if ((flags and 0x08) != 0) i += 2
        val rr = ArrayList<Double>(4)
        if ((flags and 0x10) != 0) {
            while (i + 1 < data.size) {
                val raw = u16(data, i)
                rr.add(raw * 1000.0 / 1024.0)
                i += 2
            }
        }
        return Parsed(bpm, contact, rr.toDoubleArray())
    }

    private fun u16(d: ByteArray, i: Int): Int =
        (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)
}

// =====================================================================================
// Polar H10 over native BLE GATT
// =====================================================================================

@SuppressLint("MissingPermission")
class PolarH10BleConnector(
    context: Context,
    private val deviceAddress: String? = null,
    private val namePrefix: String = "Polar H10"
) : WearableConnector {

    companion object {
        val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }

    override val displayName: String = "Polar H10"
    override val providesRawIbi: Boolean = true

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothAdapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile
    private var listener: WearableSensorListener? = null

    @Volatile
    private var stopped = true
    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var reconnectAttempt = 0

    override fun start(listener: WearableSensorListener) {
        this.listener = listener
        stopped = false
        reconnectAttempt = 0
        handler.post { beginConnection() }
    }

    override fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        handler.post {
            stopScan()
            gatt?.let {
                try { it.disconnect() } catch (_: Exception) {}
                try { it.close() } catch (_: Exception) {}
            }
            gatt = null
            listener?.onConnectionStateChanged(ConnectionState.IDLE, "Polar H10 stopped")
            listener = null
        }
    }

    private fun report(state: ConnectionState, detail: String?) {
        listener?.onConnectionStateChanged(state, detail)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) granted(Manifest.permission.BLUETOOTH_CONNECT) else true

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) granted(Manifest.permission.BLUETOOTH_SCAN)
        else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun beginConnection() {
        if (stopped) return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            report(ConnectionState.ERROR, "Bluetooth is off or unavailable")
            scheduleReconnect()
            return
        }
        if (!hasConnectPermission()) {
            report(ConnectionState.ERROR, "BLUETOOTH_CONNECT permission not granted")
            return
        }
        if (!deviceAddress.isNullOrBlank()) {
            try {
                connect(adapter.getRemoteDevice(deviceAddress))
            } catch (e: IllegalArgumentException) {
                report(ConnectionState.ERROR, "Invalid Polar H10 address: $deviceAddress")
            }
            return
        }
        if (!hasScanPermission()) {
            report(ConnectionState.ERROR, "Bluetooth scan permission not granted")
            return
        }
        startScan(adapter)
    }

    private fun startScan(adapter: BluetoothAdapter) {
        val le = adapter.bluetoothLeScanner
        if (le == null) {
            report(ConnectionState.ERROR, "BLE scanner unavailable")
            scheduleReconnect()
            return
        }
        scanner = le
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        report(ConnectionState.SCANNING, "Scanning for $namePrefix")
        le.startScan(filters, settings, scanCallback)
        handler.postDelayed({
            if (scanning && !stopped) {
                stopScan()
                report(ConnectionState.ERROR, "$namePrefix not found")
                scheduleReconnect()
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        if (scanning) {
            try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
            scanning = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val name = result.device.name ?: result.scanRecord?.deviceName ?: ""
            if (name.startsWith(namePrefix, ignoreCase = true)) {
                stopScan()
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            report(ConnectionState.ERROR, "Scan failed (code $errorCode)")
            scheduleReconnect()
        }
    }

    private fun connect(device: BluetoothDevice) {
        if (stopped) return
        report(ConnectionState.CONNECTING, "Connecting to ${device.address}")
        gatt?.let { try { it.close() } catch (_: Exception) {} }
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun scheduleReconnect() {
        if (stopped) return
        val delay = min(MAX_BACKOFF_MS, 2_000L shl min(reconnectAttempt, 4))
        reconnectAttempt++
        handler.postDelayed({ beginConnection() }, delay)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectAttempt = 0
                    report(ConnectionState.CONNECTED, "Connected, discovering services")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    try { g.close() } catch (_: Exception) {}
                    if (gatt === g) gatt = null
                    report(ConnectionState.DISCONNECTED, "Disconnected (status $status)")
                    if (!stopped) scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                report(ConnectionState.ERROR, "Service discovery failed ($status)")
                g.disconnect()
                return
            }
            val service = g.getService(HEART_RATE_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)
            if (characteristic == null) {
                report(ConnectionState.ERROR, "Heart Rate Service 0x180D / 0x2A37 not found")
                g.disconnect()
                return
            }
            enableNotifications(g, characteristic)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    report(ConnectionState.CONNECTED, "Streaming heart rate and RR intervals")
                } else {
                    report(ConnectionState.ERROR, "Enabling notifications failed ($status)")
                }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) handleMeasurement(value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) handleMeasurement(characteristic.value)
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }
    }

    private fun handleMeasurement(data: ByteArray?) {
        val parsed = HeartRateMeasurementParser.parse(data) ?: return
        if (parsed.contactDetected == false) return
        val now = SystemClock.elapsedRealtime()
        val target = listener ?: return
        if (parsed.rrMs.isNotEmpty()) target.onIbiIntervals(now, parsed.rrMs)
        if (parsed.bpm > 0) target.onHeartRateBpm(now, parsed.bpm.toDouble())
    }
}

// =====================================================================================
// Optical spike patch (Pixel Watch 41mm worn on the right wrist)
// =====================================================================================

class HeartRateSpikeFilter(
    private val windowSize: Int = 9,
    private val warmupSamples: Int = 5,
    private val spikeRatio: Double = 1.6,
    private val maxConsecutiveRejects: Int = 8
) {
    private val bpmWindow = ArrayDeque<Double>()
    private val ibiWindow = ArrayDeque<Double>()
    private var bpmRejects = 0
    private var ibiRejects = 0

    @Synchronized
    fun acceptBpm(bpm: Double): Boolean {
        if (!bpm.isFinite() || bpm <= 0.0 || bpm > 230.0) return false
        if (bpmWindow.size < warmupSamples) {
            push(bpmWindow, bpm)
            return true
        }
        val median = median(bpmWindow)
        if (bpm >= spikeRatio * median) {
            bpmRejects++
            if (bpmRejects >= maxConsecutiveRejects) {
                bpmWindow.clear()
                bpmRejects = 0
                push(bpmWindow, bpm)
                return true
            }
            return false
        }
        bpmRejects = 0
        push(bpmWindow, bpm)
        return true
    }

    @Synchronized
    fun filterIbi(ibiMs: DoubleArray): DoubleArray {
        val out = ArrayList<Double>(ibiMs.size)
        for (v in ibiMs) {
            if (!v.isFinite() || v <= 0.0) continue
            if (ibiWindow.size < warmupSamples) {
                push(ibiWindow, v)
                out.add(v)
                continue
            }
            val median = median(ibiWindow)
            if (v <= median / spikeRatio) {
                ibiRejects++
                if (ibiRejects >= maxConsecutiveRejects) {
                    ibiWindow.clear()
                    ibiRejects = 0
                    push(ibiWindow, v)
                    out.add(v)
                }
                continue
            }
            ibiRejects = 0
            push(ibiWindow, v)
            out.add(v)
        }
        return out.toDoubleArray()
    }

    @Synchronized
    fun reset() {
        bpmWindow.clear()
        ibiWindow.clear()
        bpmRejects = 0
        ibiRejects = 0
    }

    private fun push(w: ArrayDeque<Double>, v: Double) {
        w.addLast(v)
        if (w.size > windowSize) w.removeFirst()
    }

    private fun median(w: ArrayDeque<Double>): Double {
        val a = w.toDoubleArray()
        java.util.Arrays.sort(a)
        val n = a.size
        return if (n % 2 == 1) a[n / 2] else 0.5 * (a[n / 2 - 1] + a[n / 2])
    }
}

class SpikeFilteringListener(
    private val delegate: WearableSensorListener,
    private val filter: HeartRateSpikeFilter = HeartRateSpikeFilter()
) : WearableSensorListener {

    override fun onIbiIntervals(timestampMs: Long, ibiMs: DoubleArray) {
        val clean = filter.filterIbi(ibiMs)
        if (clean.isNotEmpty()) delegate.onIbiIntervals(timestampMs, clean)
    }

    override fun onHeartRateBpm(timestampMs: Long, bpm: Double) {
        if (filter.acceptBpm(bpm)) delegate.onHeartRateBpm(timestampMs, bpm)
    }

    override fun onAccelerometer(timestampMs: Long, x: Float, y: Float, z: Float) {
        delegate.onAccelerometer(timestampMs, x, y, z)
    }

    override fun onConnectionStateChanged(state: ConnectionState, detail: String?) {
        delegate.onConnectionStateChanged(state, detail)
    }
}