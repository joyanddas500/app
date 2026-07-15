package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class ScreenRefreshService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingButton: FloatingButtonView? = null
    private var buttonParams: WindowManager.LayoutParams? = null

    // Handler to execute delayed removal of the refresh overlay
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val NOTIFICATION_ID = 90210
        private const val CHANNEL_ID = "screen_refresh_service_channel"
        private const val CHANNEL_NAME = "Screen Redraw Background Service"

        @Volatile
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundServiceCompat()
        setupFloatingButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keeps the service running until explicitly stopped
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Initializes and displays the circular floating redrawing button.
     */
    private fun setupFloatingButton() {
        val sizePx = dpToPx(60) // 60dp diameter

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        buttonParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Position the button on the middle-right of the screen on startup
            val displayMetrics = resources.displayMetrics
            x = displayMetrics.widthPixels - sizePx - dpToPx(16)
            y = displayMetrics.heightPixels / 2 - sizePx / 2
        }

        floatingButton = FloatingButtonView(this)
        floatingButton?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoving = false
            private var touchStartTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val layoutParams = buttonParams ?: return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        touchStartTime = System.currentTimeMillis()
                        // Provide touch feedback visual state
                        floatingButton?.setPressedState(true)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY

                        // Treat as movement only if distance is greater than 10 pixels (slight slop)
                        if (!isMoving && sqrt(dx * dx + dy * dy) > 10) {
                            isMoving = true
                        }

                        if (isMoving) {
                            layoutParams.x = initialX + dx.toInt()
                            layoutParams.y = initialY + dy.toInt()
                            // Keep the floating button within reasonable screen boundaries
                            val displayMetrics = resources.displayMetrics
                            val maxLimitX = displayMetrics.widthPixels - sizePx
                            val maxLimitY = displayMetrics.heightPixels - sizePx
                            if (layoutParams.x < 0) layoutParams.x = 0
                            if (layoutParams.y < 0) layoutParams.y = 0
                            if (layoutParams.x > maxLimitX) layoutParams.x = maxLimitX
                            if (layoutParams.y > maxLimitY) layoutParams.y = maxLimitY

                            windowManager.updateViewLayout(v, layoutParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        floatingButton?.setPressedState(false)
                        val duration = System.currentTimeMillis() - touchStartTime
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        val distance = sqrt(dx * dx + dy * dy)

                        // If touch was short and didn't move significantly, it is a click/tap
                        if (!isMoving && duration < 250 && distance < 15) {
                            triggerHardwareRedraw()
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingButton, buttonParams)
    }

    /**
     * Executes the hardware refresh action by overlaying a solid black, non-focusable, non-touchable view
     * over the entire display for exactly 100 milliseconds to trigger GPU layer recomposition (SurfaceFlinger redraw)
     * without blocking or stealing focus from the game or app currently in the foreground.
     */
    private fun triggerHardwareRedraw() {
        // Visual indicator on the floating button that it's redrawing
        floatingButton?.startActionFeedback()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Layout Parameters for full screen coverage including notches and cutouts
        val redrawParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )

        // Create the full screen black solid view programmatically
        val redrawView = View(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        try {
            // Adding this view forces SurfaceFlinger to composite a new fullscreen layer
            windowManager.addView(redrawView, redrawParams)

            // Broadcast the redraw event for active logging in the MainActivity UI
            val broadcastIntent = Intent("com.example.screenrefresh.ACTION_REDRAW_TRIGGERED")
            sendBroadcast(broadcastIntent)

            // Remove after exactly 100 milliseconds
            mainHandler.postDelayed({
                try {
                    windowManager.removeView(redrawView)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Starts this service as a Foreground Service with a persistent status notification
     * to prevent Android from aggressively terminating it in low-memory conditions.
     */
    private fun startForegroundServiceCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the screen redraw button active in the background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Screen Redraw Utility Active")
            .setContentText("Use the floating overlay button to force display refresh.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        // Safely remove the floating button from the WindowManager on termination
        floatingButton?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    /**
     * A beautiful, custom-drawn floating button that mimics an elegant AssistiveTouch control.
     * Features smooth visual feedback on tap, custom outer glass circle, glowing accents, and high fidelity graphics.
     */
    private inner class FloatingButtonView(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E01A1C1E") // Rich dark slate glass background
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4DFFFFFF") // Semi-transparent white border for surface depth
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2).toFloat()
        }

        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4000E6FF") // Subtle cyber cyan glow highlight on click
            style = Paint.Style.FILL
        }

        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF00E6FF") // Modern cyan highlight color
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(3).toFloat()
            strokeCap = Paint.Cap.ROUND
        }

        private val iconArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF00E6FF")
            style = Paint.Style.FILL
        }

        private var isPressedState = false
        private var isActionActive = false

        fun setPressedState(pressed: Boolean) {
            isPressedState = pressed
            // Change color and redraw
            bgPaint.color = if (pressed) Color.parseColor("#F52A2C2F") else Color.parseColor("#E01A1C1E")
            invalidate()
        }

        fun startActionFeedback() {
            isActionActive = true
            invalidate()
            // Turn off action flash after 250 milliseconds
            mainHandler.postDelayed({
                isActionActive = false
                invalidate()
            }, 250)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = (width / 2f) - dpToPx(4)

            // 1. Draw glowing inner state during user touch or refresh action
            if (isPressedState || isActionActive) {
                canvas.drawCircle(cx, cy, radius, activePaint)
            }

            // 2. Draw outer glass container
            canvas.drawCircle(cx, cy, radius, bgPaint)
            canvas.drawCircle(cx, cy, radius, borderPaint)

            // 3. Draw a modern, elegant "redraw / refresh" arrow loop
            val iconRadius = radius * 0.45f
            val rect = RectF(cx - iconRadius, cy - iconRadius, cx + iconRadius, cy + iconRadius)
            
            // Draw 270 degree arc representing refresh loop
            val startAngle = -45f
            val sweepAngle = 270f
            canvas.drawArc(rect, startAngle, sweepAngle, false, iconPaint)

            // Draw the arrow head at the end of the arc
            // At -45 deg + 270 deg = 225 deg.
            val arrowAngleRad = Math.toRadians(225.0)
            val arrowX = cx + iconRadius * Math.cos(arrowAngleRad).toFloat()
            val arrowY = cy + iconRadius * Math.sin(arrowAngleRad).toFloat()

            // Calculate arrow direction vector perpendicular to radius
            val path = android.graphics.Path().apply {
                moveTo(arrowX, arrowY)
                lineTo(arrowX - dpToPx(6), arrowY + dpToPx(2))
                lineTo(arrowX + dpToPx(2), arrowY + dpToPx(6))
                close()
            }
            
            // Save & rotate slightly if performing refresh
            if (isActionActive) {
                canvas.save()
                canvas.rotate(45f, cx, cy)
                canvas.drawPath(path, iconArrowPaint)
                canvas.restore()
            } else {
                canvas.drawPath(path, iconArrowPaint)
            }
        }
    }
}
