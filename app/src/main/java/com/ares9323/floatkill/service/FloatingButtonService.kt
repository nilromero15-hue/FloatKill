package com.ares9323.floatkill.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ares9323.floatkill.MainActivity
import com.ares9323.floatkill.R
import com.ares9323.floatkill.kill.KillStrategy
import com.ares9323.floatkill.util.PermissionHelper
import kotlin.math.abs

/**
 * Foreground service that hosts the floating overlay button.
 *
 * Behaviour:
 *  - Short tap: kill + relaunch the topmost foreground app
 *  - Long press: popup menu (kill only / stop service / open settings)
 *  - Drag: move the button freely; on release, snap to nearest horizontal edge
 *
 * Position is persisted in SharedPreferences.
 */
class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var bubbleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var strategy: KillStrategy? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        startInForeground()
        resolveStrategy()
        addBubble()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        removeBubble()
        super.onDestroy()
    }

    // region — foreground notification

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.floating_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPendingIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingButtonService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_floating_button)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openAppPendingIntent)
            .addAction(0, getString(R.string.notif_stop), stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // endregion

    private fun resolveStrategy() {
        strategy = when (val s = PermissionHelper.resolve(this)) {
            is PermissionHelper.Status.Ready -> s.strategy
            PermissionHelper.Status.SetupRequired -> null
        }
        if (strategy == null) {
            toast(getString(R.string.no_kill_permission))
        }
    }

    // region — overlay bubble

    private fun addBubble() {
        if (bubbleView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.view_floating_button, null, false)
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, 0)
            y = prefs.getInt(KEY_Y, 300)
        }

        attachTouchHandler(view, params)

        try {
            windowManager.addView(view, params)
            bubbleView = view
            layoutParams = params
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot add overlay view — overlay permission missing?", t)
            toast(getString(R.string.error_overlay_permission))
            stopSelf()
        }
    }

    private fun removeBubble() {
        val view = bubbleView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Throwable) {
            // already detached
        }
        bubbleView = null
        layoutParams = null
    }

    private fun attachTouchHandler(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchDownX = 0f
            private var touchDownY = 0f
            private var dragging = false
            private var downTime = 0L
            private var longPressFired = false
            private val touchSlop = android.view.ViewConfiguration.get(this@FloatingButtonService).scaledTouchSlop
            private val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
            private val longPressRunnable = Runnable {
                if (!dragging) {
                    longPressFired = true
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    showContextMenu(view)
                }
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchDownX = event.rawX
                        touchDownY = event.rawY
                        dragging = false
                        longPressFired = false
                        downTime = System.currentTimeMillis()
                        v.postDelayed(longPressRunnable, longPressTimeout)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchDownX).toInt()
                        val dy = (event.rawY - touchDownY).toInt()
                        if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            dragging = true
                            v.removeCallbacks(longPressRunnable)
                        }
                        if (dragging) {
                            params.x = initialX + dx
                            params.y = initialY + dy
                            try {
                                windowManager.updateViewLayout(v, params)
                            } catch (_: Throwable) { /* ignore */ }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.removeCallbacks(longPressRunnable)
                        if (dragging) {
                            snapToEdge(v, params)
                            saveBubblePosition(params)
                        } else if (!longPressFired) {
                            onBubbleClicked()
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(longPressRunnable)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val viewWidth = if (view.width > 0) view.width else view.measuredWidth
        val centerX = params.x + viewWidth / 2
        params.x = if (centerX < screenWidth / 2) 0 else screenWidth - viewWidth
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Throwable) { /* ignore */ }
    }

    private fun saveBubblePosition(params: WindowManager.LayoutParams) {
        prefs.edit()
            .putInt(KEY_X, params.x)
            .putInt(KEY_Y, params.y)
            .apply()
    }

    // endregion

    // region — actions

    private fun onBubbleClicked() {
    val service = ForegroundDetectorService.instance()

    if (service == null) {
        toast("Accessibility service unavailable")
        return
    }

    service.performGuildWarsEmergencyLogout { success ->
        if (!success) {
            toast("Guild Wars emergency logout failed")
        }
    }
}

    private fun showContextMenu(anchor: View) {
        // Anchor is on an overlay window, so PopupMenu can't anchor there directly;
        // we fall back to a translucent activity-style options. Use simple
        // AlertDialog via the system overlay — but dialogs need an Activity context.
        // Easiest: open MainActivity with a flag, OR show a windowed PopupMenu using
        // an invisible anchor on the overlay. Practical option: spawn a PopupMenu
        // using the overlay context — works on most devices since API 21+.
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_KILL_ONLY, 0, R.string.menu_kill_only)
        popup.menu.add(0, MENU_STOP_SERVICE, 1, R.string.menu_stop_service)
        popup.menu.add(0, MENU_OPEN_SETTINGS, 2, R.string.menu_settings)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_KILL_ONLY -> killOnly()
                MENU_STOP_SERVICE -> stopSelf()
                MENU_OPEN_SETTINGS -> openSettings()
            }
            true
        }
        try {
            popup.show()
        } catch (t: Throwable) {
            Log.w(TAG, "PopupMenu failed on overlay context, falling back to MainActivity", t)
            openSettings()
        }
    }

    private fun killOnly() {
        val target = currentTargetPackage() ?: return
        val killer = strategy ?: run {
            toast(getString(R.string.no_kill_permission))
            return
        }
        val ok = killer.kill(target)
        if (!ok) {
            val reason = killer.lastError ?: target
            toast(getString(R.string.kill_failed_detail, target, reason))
        }
    }

    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    /**
     * Returns the package of the currently visible foreground app,
     * filtering out our own package and system surfaces.
     */
    private fun currentTargetPackage(): String? {
        val pkg = ForegroundDetectorService.currentPackage
        if (pkg.isNullOrEmpty()) {
            toast(getString(R.string.no_foreground_detected))
            return null
        }
        if (pkg == packageName) {
            toast(getString(R.string.refusing_self))
            return null
        }
        if (isSystemPackage(pkg)) {
            toast(getString(R.string.refusing_system, pkg))
            return null
        }
        return pkg
    }

    private fun isSystemPackage(pkg: String): Boolean {
        // Plan rule: never touch obvious Android/system surfaces.
        if (pkg == "android") return true
        if (pkg.startsWith("com.android.systemui")) return true
        if (pkg.startsWith("com.android.launcher")) return true
        // Be conservative on anything starting with com.android. — these are
        // first-party system apps that don't benefit from being killed.
        return pkg.startsWith("com.android.")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // endregion

    companion object {
        private const val TAG = "FloatingButtonService"

        const val ACTION_STOP = "com.ares9323.floatkill.action.STOP"

        private const val CHANNEL_ID = "floating_button"
        private const val NOTIFICATION_ID = 1001

        private const val PREFS = "float_kill_prefs"
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"

        private const val MENU_KILL_ONLY = 1
        private const val MENU_STOP_SERVICE = 2
        private const val MENU_OPEN_SETTINGS = 3

        @Volatile
        var running: Boolean = false
            private set
    }
}
