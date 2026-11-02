package com.universal.sleepapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.universal.sleepapp.engine.SleepEngine
import com.universal.sleepapp.engine.Stage
import com.universal.sleepapp.service.SleepTrackingService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var trackingService: SleepTrackingService? = null
    private var isBound by mutableStateOf(false)

    // Reactive UI state polled from the service
    private var isTrackingActive by mutableStateOf(false)
    private var connectionStatus by mutableStateOf("Disconnected")
    private var stagesList by mutableStateOf<List<Stage>>(emptyList())
    private var sessionStartTimeMs by mutableStateOf(0L)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? SleepTrackingService.LocalBinder
            trackingService = localBinder?.getService()
            isBound = true
            updateStateFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            trackingService = null
            isBound = false
            isTrackingActive = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startTrackingService()
        } else {
            connectionStatus = "Permissions denied by user"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF9D84EC),
                    secondary = Color(0xFF6C5CE7),
                    background = Color(0xFF121218),
                    surface = Color(0xFF1E1E28),
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SleepTrackerScreen(
                        isTracking = isTrackingActive,
                        connectionDetail = connectionStatus,
                        stages = stagesList,
                        startTimeMs = sessionStartTimeMs,
                        onStartClicked = { checkPermissionsAndStart() },
                        onStopClicked = { stopTrackingService() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, SleepTrackingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {

        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun updateStateFromService() {
        val service = trackingService ?: return
        isTrackingActive = SleepTrackingService.isRunning
        connectionStatus = service.getConnectionDetail()
        stagesList = service.getStabilizedStages()
        sessionStartTimeMs = service.getSessionStartWallMs()
    }

    @Composable
    private fun SleepTrackerScreen(
        isTracking: Boolean,
        connectionDetail: String,
        stages: List<Stage>,
        startTimeMs: Long,
        onStartClicked: () -> Unit,
        onStopClicked: () -> Unit
    ) {
        // Poll service state periodically while tracking is active
        LaunchedEffect(isTracking) {
            while (isTracking) {
                updateStateFromService()
                delay(2000L)
            }
        }

        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Sleep Monitor",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .padding(top = 16.dp, bottom = 24.dp)
                    .align(Alignment.Start)
            )

            // Dynamic Live Hypnogram Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Real-Time Hypnogram",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (stages.isNotEmpty()) {
                        HypnogramChart(stages = stages, modifier = Modifier.fillMaxSize())
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isTracking) "Collecting epoch data..." else "Start a session to record stages",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Stage Summary Stats Grid
            val summary = SleepEngine.summarize(stages, 30)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricCard("Deep", "${summary[Stage.DEEP]?.toInt() ?: 0}m", Color(0xFF4A90E2), Modifier.weight(1f))
                Spacer(modifier = Modifier.width(8.dp))
                MetricCard("REM", "${summary[Stage.REM]?.toInt() ?: 0}m", Color(0xFF9013FE), Modifier.weight(1f))
                Spacer(modifier = Modifier.width(8.dp))
                MetricCard("Light", "${summary[Stage.LIGHT]?.toInt() ?: 0}m", Color(0xFF50E3C2), Modifier.weight(1f))
                Spacer(modifier = Modifier.width(8.dp))
                MetricCard("Awake", "${summary[Stage.AWAKE]?.toInt() ?: 0}m", Color(0xFFF5A623), Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Connection & Status Monitor
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (isTracking) Color.Green else Color.Red)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isTracking) "Tracking Active" else "Engine Idle",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = connectionDetail,
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Session Control Button
            Button(
                onClick = {
                    if (isTracking) onStopClicked() else onStartClicked()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTracking) Color(0xFFE53935) else MaterialTheme.colorScheme.secondary
                )
            ) {
                Text(
                    text = if (isTracking) "Stop Sleep Tracking" else "Start Night Tracking",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    private fun MetricCard(title: String, value: String, accentColor: Color, modifier: Modifier) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = title, fontSize = 11.sp, color = Color.Gray)
                Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun HypnogramChart(stages: List<Stage>, modifier: Modifier = Modifier) {
        Canvas(modifier = modifier) {
            val width = size.width
            val height = size.height
            val pointsCount = stages.size

            if (pointsCount < 2) return@Canvas

            val dx = width / (pointsCount - 1)
            // Stage mapping: AWAKE = top (0), REM = 1/3, LIGHT = 2/3, DEEP = bottom (1)
            fun stageToY(stage: Stage): Float = when (stage) {
                Stage.AWAKE -> height * 0.1f
                Stage.REM -> height * 0.35f
                Stage.LIGHT -> height * 0.65f
                Stage.DEEP -> height * 0.9f
            }

            val path = Path()
            path.moveTo(0f, stageToY(stages.first()))

            for (i in 1 until pointsCount) {
                val x = i * dx
                val y = stageToY(stages[i])
                path.lineTo(x, y)
            }

            drawPath(
                path = path,
                color = Color(0xFF9D84EC),
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }

    // ------------------------------------------------------------------ Permission Flow
    private fun checkPermissionsAndStart() {
        val requiredPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= 20) {
            requiredPermissions.add(Manifest.permission.BODY_SENSORS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startTrackingService()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startTrackingService() {
        val intent = SleepTrackingService.startIntent(
            context = this,
            source = SleepTrackingService.SOURCE_SAMSUNG,
            restingBpm = 60.0,
            usePhoneAccelerometer = true
        )
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isTrackingActive = true
    }

    private fun stopTrackingService() {
        startService(SleepTrackingService.stopIntent(this))
        isTrackingActive = false
    }
}