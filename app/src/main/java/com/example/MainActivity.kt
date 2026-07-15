package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    // Dynamic states for composition
    private val isPermissionGrantedState = mutableStateOf(false)
    private val isServiceRunningState = mutableStateOf(false)
    private val logsList = mutableStateListOf<String>()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Broadcast receiver to listen for screen redraw signals from the background floating button
    private val redrawReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.screenrefresh.ACTION_REDRAW_TRIGGERED") {
                logEvent("Redraw event triggered via Floating Button")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Log application startup
        logEvent("System dashboard initialized.")

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    DashboardScreen(
                        isPermissionGranted = isPermissionGrantedState.value,
                        isServiceRunning = isServiceRunningState.value,
                        logs = logsList,
                        onGrantPermissionClick = { requestOverlayPermission() },
                        onToggleService = { start -> toggleScreenRefreshService(start) },
                        onManualRedrawTrigger = { triggerLocalRedraw() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Check overlay permission and update local states
        val currentPermission = checkOverlayPermission()
        if (currentPermission != isPermissionGrantedState.value) {
            isPermissionGrantedState.value = currentPermission
            logEvent("Overlay permission checked. Status: " + if (currentPermission) "GRANTED" else "DENIED")
        }

        // Check if the background service is actively running and sync the state
        val currentServiceState = ScreenRefreshService.isRunning
        if (currentServiceState != isServiceRunningState.value) {
            isServiceRunningState.value = currentServiceState
            logEvent("Service background state synced: " + if (currentServiceState) "ACTIVE" else "INACTIVE")
        }

        // Register receiver for real-time overlay action telemetry
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(
                    redrawReceiver,
                    IntentFilter("com.example.screenrefresh.ACTION_REDRAW_TRIGGERED"),
                    RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(
                    redrawReceiver,
                    IntentFilter("com.example.screenrefresh.ACTION_REDRAW_TRIGGERED")
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(redrawReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Appends a formatted timestamped record to the active event telemetry logs list.
     */
    private fun logEvent(message: String) {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val timeStr = sdf.format(Date())
        logsList.add(0, "[$timeStr] $message") // Insert at head to show newest logs first
    }

    /**
     * Checks if the SYSTEM_ALERT_WINDOW ("Draw over other apps") permission is granted.
     */
    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    /**
     * Launches the system settings panel for overlay permissions to guide the user.
     */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            logEvent("Redirecting to System settings for Draw Over other Apps...")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "Permission already granted (Pre-M device)", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Controls Starting/Stopping the background Overlay Service.
     */
    private fun toggleScreenRefreshService(start: Boolean) {
        if (!checkOverlayPermission() && start) {
            logEvent("ERROR: Cannot start service. Overlay permission is missing.")
            Toast.makeText(this, "Please grant overlay permission first!", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, ScreenRefreshService::class.java)
        if (start) {
            logEvent("Starting ScreenRefreshService...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            // Add a small delay to read the state again
            mainHandler.postDelayed({
                isServiceRunningState.value = ScreenRefreshService.isRunning
                logEvent("ScreenRefreshService started successfully. Floating bubble is active.")
            }, 300)
        } else {
            logEvent("Stopping ScreenRefreshService...")
            stopService(serviceIntent)
            isServiceRunningState.value = false
            logEvent("ScreenRefreshService stopped. Floating bubble removed.")
        }
    }

    /**
     * Forces a localized 100ms full screen solid black redraw.
     * Demonstrates how the system redrawing overlay functions.
     */
    private fun triggerLocalRedraw() {
        logEvent("Manual redraw requested. Initializing full-screen GPU flash...")

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Layout Parameters:
        // FLAG_NOT_FOCUSABLE: Ensures this view never steals key or touch input focus from underlying windows (like games).
        // FLAG_NOT_TOUCHABLE: Passes all touchscreen taps through the view directly into the content underneath.
        // FLAG_LAYOUT_IN_SCREEN & FLAG_LAYOUT_NO_LIMITS: Forces the black layout to render behind system notches/cutouts, fully resetting all framebuffer pixels.
        val redrawParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (checkOverlayPermission()) layoutType else WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )

        val redrawView = View(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        try {
            windowManager.addView(redrawView, redrawParams)
            logEvent("Overlay View added. SurfaceFlinger forcing framebuffer redraw...")

            mainHandler.postDelayed({
                try {
                    windowManager.removeView(redrawView)
                    logEvent("Overlay View removed. Redraw cycle (100ms) completed.")
                } catch (e: Exception) {
                    logEvent("Error removing redraw overlay: ${e.message}")
                }
            }, 100)
        } catch (e: Exception) {
            logEvent("Error injecting redraw layer: ${e.message}. (Need Overlay Permission for System-wide Redraws)")
            // Fallback: Show a local toast/flash inside the activity if permission not granted
            Toast.makeText(this, "Overlay permission is required for full system redrawing!", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun DashboardScreen(
    isPermissionGranted: Boolean,
    isServiceRunning: Boolean,
    logs: List<String>,
    onGrantPermissionClick: () -> Unit,
    onToggleService: (Boolean) -> Unit,
    onManualRedrawTrigger: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // Neon rotating animation for title icon
    val infiniteTransition = rememberInfiniteTransition(label = "icon_rotation")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = { it }),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header with Glowing Cyan styling
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    .background(Color(0x1A00E6FF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh logo",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(28.dp)
                        .rotate(rotationAngle)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "SCREEN REDRAW",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "Anti-Freezing Display Utility",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        // SECTION 1: System Permissions Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStrokeWrapper(isPermissionGranted, Color(0xFF30D158), Color(0xFFFF9F0A))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isPermissionGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = "Status icon",
                        tint = if (isPermissionGranted) Color(0xFF30D158) else Color(0xFFFF9F0A),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "System Overlay Access",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (isPermissionGranted) {
                        "Permission is ACTIVE. The application has authorization to draw the floating refresh controller over other applications."
                    } else {
                        "Permission is REQUIRED. To display the floating redraw button over your games and apps, you must enable \"Draw over other apps\" in system settings."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (!isPermissionGranted) {
                    Button(
                        onClick = onGrantPermissionClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF9F0A),
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings icon",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ENABLE OVERLAY ACCESS",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x1A30D158), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Check Icon",
                            tint = Color(0xFF30D158),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Overlay Permission is fully active.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF30D158),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // SECTION 2: Background Overlay Control (Switch)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Service Overlay Layer",
                            tint = if (isServiceRunning) MaterialTheme.colorScheme.primary else Color(0xFF94A3B8),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Floating Refresh Button",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isServiceRunning) "Status: RUNNING" else "Status: STOPPED",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isServiceRunning) Color(0xFF30D158) else Color(0xFF94A3B8)
                            )
                        }
                    }

                    Switch(
                        checked = isServiceRunning,
                        onCheckedChange = { onToggleService(it) },
                        enabled = isPermissionGranted,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = Color(0x3300E6FF),
                            uncheckedThumbColor = Color(0xFF475569),
                            uncheckedTrackColor = Color(0xFF1E293B)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Enables a small, semi-transparent circular overlay button (similar to iOS AssistiveTouch). You can drag this button anywhere. Click it to instantly clear screen stutters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                    lineHeight = 18.sp
                )
            }
        }

        // SECTION 3: Manual Trigger Redraw Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Redraw Simulation / Immediate Test",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Execute the exact 100ms hardware overlay layer composition programmatically right now to inspect how SurfaceFlinger resets your active layout layers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onManualRedrawTrigger,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color(0xFF001E22)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Icon",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TRIGGER 100MS HARDWARE REDRAW",
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // SECTION 4: Architecture Explanation (Why does this work?)
        var expandedExplanation by remember { mutableStateOf(false) }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expandedExplanation = !expandedExplanation },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Architecture Info",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "How the Window Flags Work",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Expand icon",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.rotate(if (expandedExplanation) 90f else 0f)
                    )
                }

                AnimatedVisibility(
                    visible = expandedExplanation,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Older Android displays suffer from graphical freezing when active layout structures stagnate in framebuffers. This app forces layer recomposition in 4 seamless stages:",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8),
                            lineHeight = 18.sp
                        )

                        // Step details
                        StepItem(
                            stepNumber = "1",
                            stepTitle = "User Interaction",
                            stepDesc = "You click the floating AssistiveTouch-style button overlay."
                        )
                        StepItem(
                            stepNumber = "2",
                            stepTitle = "Overlay Layer Insertion",
                            stepDesc = "The utility programmatically injects a solid black full-screen View via the WindowManager with specialized parameters."
                        )
                        StepItem(
                            stepNumber = "3",
                            stepTitle = "FLAG_NOT_FOCUSABLE & FLAG_NOT_TOUCHABLE",
                            stepDesc = "Crucial! By passing these flags, the black layer does not grab input focus. Touches pass directly through the view. Foreground games or movies remain completely uninterrupted and do not pause."
                        )
                        StepItem(
                            stepNumber = "4",
                            stepTitle = "Automated Layer Disposal",
                            stepDesc = "After exactly 100ms, the overlay is removed. This double-cycle (Overlay Added -> Overlay Removed) forces SurfaceFlinger to execute a total GPU recomposition of display frames, resetting the graphical framebuffers and resolving freezes instantly."
                        )
                    }
                }
            }
        }

        // SECTION 5: Live Activity Logs
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF0C0E12) // Pure terminal black
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Logs terminal",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Live Redraw Activity Log",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE2E8F0)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isServiceRunning) Color(0xFF30D158) else Color(0xFFFF9F0A))
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No system events recorded yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF475569)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        logs.forEach { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                ),
                                color = if (log.contains("ERROR")) Color(0xFFFF453A)
                                        else if (log.contains("Redraw") || log.contains("successfully")) Color(0xFF30D158)
                                        else Color(0xFF38BDF8)
                            )
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
fun StepItem(stepNumber: String, stepTitle: String, stepDesc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x0AFFFFFF), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNumber,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = Color.Black
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = stepTitle,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stepDesc,
                fontSize = 11.sp,
                color = Color(0xFF94A3B8),
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
fun BorderStrokeWrapper(condition: Boolean, colorTrue: Color, colorFalse: Color): androidx.compose.foundation.BorderStroke {
    return androidx.compose.foundation.BorderStroke(
        width = 1.dp,
        color = if (condition) colorTrue else colorFalse
    )
}

@Preview(showBackground = true)
@Composable
fun DashboardPreview() {
    MyApplicationTheme {
        DashboardScreen(
            isPermissionGranted = true,
            isServiceRunning = true,
            logs = listOf("[11:46:25] Dashboard initialized.", "[11:46:32] Floating Button Redraw forced."),
            onGrantPermissionClick = {},
            onToggleService = {},
            onManualRedrawTrigger = {}
        )
    }
}
