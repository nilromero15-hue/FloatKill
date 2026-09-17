package com.ares9323.floatkill.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 1) Tracks the topmost foreground package.
 * 2) Drives Settings-UI automation to force-stop a target app via Accessibility.
 *
 * The kill walk is window-aware to avoid clicking the underlying "Force stop"
 * button on the App info screen when the confirmation dialog is already up:
 *
 *   - When Settings opens, we capture its windowId.
 *   - The confirmation dialog appears as a separate window (different id) —
 *     we look for the positive button only inside the dialog window's subtree.
 *   - A short post-click debounce prevents reacting to stale content events.
 */
class ForegroundDetectorService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingOp: KillOp? = null
    private var opState: OpState = OpState.IDLE
    private var opAttempts: Int = 0
    private var settingsWindowId: Int = WINDOW_ID_UNSET
    private var dialogWindowId: Int = WINDOW_ID_UNSET
    private var lastClickAt: Long = 0L

    private val watchdog = Runnable {
        Log.w(TAG, "Watchdog firing on state=$opState — aborting op")
        completeOp(false, "timeout (state=$opState)")
    }

    /**
     * Independent ticker that drives the state machine even when no
     * accessibility events arrive (Samsung sometimes reuses the Settings
     * activity without re-emitting TYPE_WINDOW_STATE_CHANGED).
     */
    private val poller = object : Runnable {
        override fun run() {
            val op = pendingOp ?: return
            driveStateMachine(op)
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrEmpty()) {
                if (pendingOp == null || pkg != SETTINGS_PACKAGE) {
                    currentPackage = pkg
                }
            }
        }

        val op = pendingOp ?: return
        driveStateMachine(op)
    }

    private fun driveStateMachine(op: KillOp) {
        if (SystemClock.uptimeMillis() - lastClickAt < DEBOUNCE_AFTER_CLICK_MS) return
        when (opState) {
            OpState.OPENING_SETTINGS,
            OpState.CLICKING_FORCE_STOP -> tryClickForceStop(op)
            OpState.CONFIRMING -> tryClickConfirm(op)
            OpState.OPENING_RECENTS,
            OpState.DISMISSING_CARD -> tryDismissRecents(op)
            OpState.RETURNING, OpState.IDLE -> { /* ignore */ }
        }
    }

    override fun onInterrupt() { /* no long work to abort */ }
  fun performGuildWarsEmergencyLogout(callback: (Boolean) -> Unit) {
    val metrics = resources.displayMetrics
    val width = metrics.widthPixels.toFloat()
    val height = metrics.heightPixels.toFloat()

    // Positions measured from Guild Wars at 1536x960,
    // converted to relative screen coordinates.
    val menuX = width * (242f / 1536f)
    val menuY = height * (52f / 960f)

    val logoutX = width * (360f / 1536f)
    val logoutY = height * (464f / 960f)

    val characterX = width * (770f / 1536f)
    val characterY = height * (473f / 960f)

    Log.i(
        TAG,
        "GW logout display=${width}x${height}, " +
            "menu=($menuX,$menuY), logout=($logoutX,$logoutY), " +
            "character=($characterX,$characterY)"
    )

    performTap(menuX, menuY) { first ->
        if (!first) {
            callback(false)
            return@performTap
        }

        handler.postDelayed({
            performTap(logoutX, logoutY) { second ->
                if (!second) {
                    callback(false)
                    return@performTap
                }

                handler.postDelayed({
                    performTap(characterX, characterY) { third ->
                        callback(third)
                    }
                }, 350L)
            }
        }, 350L)
    }
}  

private fun performTap(
    x: Float,
    y: Float,
    callback: (Boolean) -> Unit
) {
    val path = android.graphics.Path().apply {
        moveTo(x, y)
    }

    val stroke =
        android.accessibilityservice.GestureDescription.StrokeDescription(
            path,
            0,
            50
        )

    val gesture =
        android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()

    try {
        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(
                    gestureDescription: android.accessibilityservice.GestureDescription?
                ) {
                    callback(true)
                }

                override fun onCancelled(
                    gestureDescription: android.accessibilityservice.GestureDescription?
                ) {
                    callback(false)
                }
            },
            null
        )
    } catch (t: Throwable) {
        Log.e(TAG, "Guild Wars tap failed at ($x,$y)", t)
        callback(false)
    }
}
    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        INSTANCE = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        connected = false
        if (INSTANCE === this) INSTANCE = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // region — kill operation driver

    fun startKillOperation(
        targetPackage: String,
        relaunchAfter: Boolean,
        callback: (success: Boolean, error: String?) -> Unit
    ) {
        if (pendingOp != null) {
            callback(false, "another kill is already in progress")
            return
        }
        val op = KillOp(targetPackage, relaunchAfter, callback)
        pendingOp = op
        opState = OpState.OPENING_SETTINGS
        opAttempts = 0
        settingsWindowId = WINDOW_ID_UNSET
        dialogWindowId = WINDOW_ID_UNSET
        lastClickAt = 0L

        Log.i(TAG, "Starting accessibility kill for $targetPackage")
        // Plain NEW_TASK only: CLEAR_TASK / CLEAR_TOP cause Samsung One UI to
        // sometimes resurrect a stale Settings instance without re-firing
        // window-state events.
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$targetPackage")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot open App info for $targetPackage", t)
            completeOp(false, "cannot open Settings: ${t.message}")
            return
        }
        armWatchdog(WATCHDOG_MS)
        startPolling()
    }

    private fun startPolling() {
        handler.removeCallbacks(poller)
        handler.postDelayed(poller, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
        handler.removeCallbacks(poller)
    }

    /**
     * Recents-flow entry point. Opens the system Recents screen and dismisses
     * the card whose label matches [appLabel] via ACTION_DISMISS.
     */
    fun startRecentsDismiss(
        targetPackage: String,
        appLabel: String,
        relaunchAfter: Boolean,
        callback: (success: Boolean, error: String?) -> Unit
    ) {
        if (pendingOp != null) {
            callback(false, "another kill is already in progress")
            return
        }
        val op = KillOp(targetPackage, relaunchAfter, callback, appLabel)
        pendingOp = op
        opState = OpState.OPENING_RECENTS
        opAttempts = 0
        settingsWindowId = WINDOW_ID_UNSET
        dialogWindowId = WINDOW_ID_UNSET
        lastClickAt = 0L
        recentsRetryCount = 0

        Log.i(TAG, "Starting recents dismiss for $targetPackage (label='$appLabel')")
        val opened = performGlobalAction(GLOBAL_ACTION_RECENTS)
        if (!opened) {
            completeOp(false, "GLOBAL_ACTION_RECENTS rejected")
            return
        }
        armWatchdog(WATCHDOG_MS)
        startPolling()
    }

    private var recentsRetryCount: Int = 0

    private fun tryDismissRecents(op: KillOp) {
        val all = try { windows } catch (_: Throwable) { null }
        if (all.isNullOrEmpty()) {
            opAttempts++
            if (opAttempts > MAX_ATTEMPTS) completeOp(false, "no windows available")
            return
        }

        val label = op.appLabel ?: op.targetPackage

        for (win in all) {
            if (win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = try { win.root } catch (_: Throwable) { null } ?: continue
            val target = findCardForLabel(root, label) ?: continue
            Log.i(
                TAG,
                "Found recents card label='$label' class=${target.node.className} " +
                    "action=${target.actionLabel}(id=${target.actionId})"
            )
            val dismissed = target.node.performAction(target.actionId)
            if (dismissed) {
                Log.i(TAG, "Dismiss action accepted")
                lastClickAt = SystemClock.uptimeMillis()
                opState = OpState.RETURNING
                scheduleHomeAndFinish(op, success = true, error = null)
                return
            }
            Log.w(TAG, "Dismiss action rejected — falling back to swipe gesture")
            if (swipeUpOnNode(target.node)) {
                lastClickAt = SystemClock.uptimeMillis()
                opState = OpState.RETURNING
                scheduleHomeAndFinish(op, success = true, error = null)
                return
            }
            Log.w(TAG, "Swipe gesture also failed for this card; continuing search")
        }

        opAttempts++
        if (opAttempts == DUMP_AFTER_ATTEMPTS) {
            Log.w(TAG, "Recents card not found after $DUMP_AFTER_ATTEMPTS polls — dumping window trees")
            dumpWindowsForDebug()
        }
        if (opAttempts > MAX_ATTEMPTS) {
            completeOp(false, "recents card for '$label' not found / not dismissable")
        }
    }

    private fun actionLabels(node: AccessibilityNodeInfo): String = try {
        node.actionList.joinToString(",") { it.label?.toString() ?: it.id.toString() }
    } catch (_: Throwable) { "?" }

    /**
     * Dispatches a swipe-up gesture covering the height of the screen, starting
     * from the centre of [node]. Used as a fallback when ACTION_DISMISS is
     * rejected by an OEM Recents implementation.
     */
    private fun swipeUpOnNode(node: AccessibilityNodeInfo): Boolean {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            Log.w(TAG, "Node has empty bounds — cannot swipe")
            return false
        }
        val cx = bounds.centerX().toFloat()
        val startY = bounds.centerY().toFloat()
        val metrics = resources.displayMetrics
        val endY = (-metrics.heightPixels / 2f).coerceAtLeast(-startY)

        val path = android.graphics.Path().apply {
            moveTo(cx, startY)
            lineTo(cx, startY + endY)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 250)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        return try {
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Log.e(TAG, "dispatchGesture threw", t)
            false
        }
    }

    /**
     * Verbose-level dump — off by default in logcat, enable with
     * `adb shell setprop log.tag.FloatKill.FGDetector VERBOSE`.
     */
    private fun dumpWindowsForDebug() {
        if (!Log.isLoggable(TAG, Log.VERBOSE)) return
        val all = try { windows } catch (_: Throwable) { null } ?: return
        Log.v(TAG, "=== Window dump: ${all.size} windows ===")
        for (win in all) {
            val root = try { win.root } catch (_: Throwable) { null }
            Log.v(TAG, "Window id=${win.id} type=${win.type} pkg=${root?.packageName}")
            if (root != null) dumpNode(root, 0)
        }
        Log.v(TAG, "=== end window dump ===")
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 10) return
        val indent = "  ".repeat(depth)
        val text = node.text?.toString()?.take(40)
        val cd = node.contentDescription?.toString()?.take(40)
        val cls = node.className?.toString()?.substringAfterLast('.')
        val id = node.viewIdResourceName
        val click = node.isClickable
        val acts = actionLabels(node)
        Log.v(TAG, "$indent[$cls] text=$text cd=$cd id=$id click=$click acts=[$acts]")
        for (i in 0 until node.childCount) {
            val c = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            dumpNode(c, depth + 1)
        }
    }

    private data class DismissTarget(
        val node: AccessibilityNodeInfo,
        val actionId: Int,
        val actionLabel: String
    )

    private fun findCardForLabel(root: AccessibilityNodeInfo, label: String): DismissTarget? {
        // Gather every node whose visible text or contentDescription matches the label.
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        candidates.addAll(root.findAccessibilityNodeInfosByText(label))
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val cd = n.contentDescription?.toString().orEmpty()
            if (cd.contains(label, ignoreCase = true)) candidates.add(n)
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (_: Throwable) { null } ?: continue
                stack.addLast(c)
            }
        }

        for (n in candidates) {
            // Strategy A — Samsung One UI: the card node itself (or an ancestor)
            // exposes a custom dismiss action (label="Close").
            findDismissTargetUpwards(n)?.let { return it }

            // Strategy B — AOSP / LG UX: the card holds a separate clickable
            // child whose id ends with ":id/dismiss_task" or whose
            // contentDescription is a localised "dismiss" verb. Find the card
            // root first (closest clickable ancestor or just the parent), then
            // hunt for the dismiss button inside.
            val cardRoot = firstClickableAncestor(n) ?: n.parent ?: continue
            findDismissButtonInCard(cardRoot)?.let { btn ->
                return DismissTarget(btn, AccessibilityNodeInfo.ACTION_CLICK, "dismiss_task")
            }
        }
        return null
    }

    /**
     * Searches [cardRoot]'s subtree for a clickable "dismiss this task" button,
     * matched either by its resource id (AOSP `:id/dismiss_task`) or by a
     * localised content description ("Dismiss" / "Ignora" / "Close" / …).
     */
    private fun findDismissButtonInCard(cardRoot: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(cardRoot)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.isClickable) {
                val id = n.viewIdResourceName.orEmpty()
                val cd = n.contentDescription?.toString().orEmpty()
                val idMatches = DISMISS_BUTTON_ID_SUFFIXES.any { id.endsWith(it) }
                val cdMatches = DISMISS_BUTTON_CD_LABELS.any { cd.equals(it, ignoreCase = true) }
                if (idMatches || cdMatches) return n
            }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (_: Throwable) { null } ?: continue
                stack.addLast(c)
            }
        }
        return null
    }

    private fun findDismissTargetUpwards(start: AccessibilityNodeInfo?): DismissTarget? {
        var n = start
        var depth = 0
        while (n != null && depth < 12) {
            val dismissAction = findDismissAction(n)
            if (dismissAction != null) {
                return DismissTarget(n, dismissAction.id, dismissAction.label?.toString() ?: "")
            }
            n = n.parent
            depth++
        }
        return null
    }

    /**
     * Returns the AccessibilityAction that closes a Recents card on this node, if any.
     * Accepts both the standard [AccessibilityNodeInfo.ACTION_DISMISS] (AOSP / Pixel)
     * and OEM custom actions labelled "Close" / localised equivalent (Samsung One UI
     * exposes the dismiss as a custom action with label="Close" on the taskView).
     */
    private fun findDismissAction(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo.AccessibilityAction? {
        val list = try { node.actionList } catch (_: Throwable) { null } ?: return null
        list.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_DISMISS }?.let { return it }
        for (a in list) {
            val label = a.label?.toString() ?: continue
            if (CLOSE_LABELS.any { label.equals(it, ignoreCase = true) }) return a
        }
        return null
    }

    private fun scheduleHomeAndFinish(op: KillOp, success: Boolean, error: String?) {
        cancelWatchdog()
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
            handler.postDelayed({
                if (success && op.relaunchAfter) relaunchTarget(op.targetPackage)
                completeOp(success, error)
            }, RELAUNCH_DELAY_MS)
        }, BACK_INTERVAL_MS)
    }

    private fun tryClickForceStop(op: KillOp) {
        val root = safeRoot() ?: return
        if (root.packageName != SETTINGS_PACKAGE) return

        if (settingsWindowId == WINDOW_ID_UNSET) {
            settingsWindowId = root.windowId
            Log.d(TAG, "Captured settings windowId=$settingsWindowId")
        }

        val node = findForceStopNode(root)
        if (node == null) {
            opAttempts++
            if (opAttempts > MAX_ATTEMPTS) {
                completeOp(false, "force-stop button not found")
            }
            return
        }

        if (!node.isEnabled) {
            Log.i(TAG, "force-stop disabled — treating as already-stopped")
            opState = OpState.RETURNING
            scheduleReturnAndFinish(op, success = true, error = null)
            return
        }

        Log.i(TAG, "Clicking force-stop for ${op.targetPackage} (class=${node.className})")
        val clickTarget = if (node.isClickable) node else firstClickableAncestor(node) ?: node
        val ok = clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!ok) {
            completeOp(false, "ACTION_CLICK rejected on force-stop button")
            return
        }
        lastClickAt = SystemClock.uptimeMillis()
        opState = OpState.CONFIRMING
        opAttempts = 0
        armWatchdog(WATCHDOG_MS)
    }

    private fun tryClickConfirm(op: KillOp) {
        // Find the dialog window — must be a different window than the settings activity.
        val dialogRoot = findDialogRoot()
        if (dialogRoot == null) {
            opAttempts++
            if (opAttempts > MAX_ATTEMPTS) {
                completeOp(false, "confirmation dialog never appeared")
            }
            return
        }

        val okNode = findDialogPositiveButton(dialogRoot)
        if (okNode == null) {
            opAttempts++
            if (opAttempts > MAX_ATTEMPTS) {
                completeOp(false, "OK button not found inside dialog")
            }
            return
        }

        Log.i(TAG, "Clicking dialog OK (class=${okNode.className} text=${okNode.text})")
        val clickTarget = if (okNode.isClickable) okNode else firstClickableAncestor(okNode) ?: okNode
        val ok = clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!ok) {
            completeOp(false, "ACTION_CLICK rejected on OK button")
            return
        }
        lastClickAt = SystemClock.uptimeMillis()
        opState = OpState.RETURNING
        scheduleReturnAndFinish(op, success = true, error = null)
    }

    private fun scheduleReturnAndFinish(op: KillOp, success: Boolean, error: String?) {
        cancelWatchdog()
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_BACK)
                handler.postDelayed({
                    if (success && op.relaunchAfter) relaunchTarget(op.targetPackage)
                    completeOp(success, error)
                }, RELAUNCH_DELAY_MS)
            }, BACK_INTERVAL_MS)
        }, BACK_INTERVAL_MS)
    }

    private fun relaunchTarget(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Log.w(TAG, "No launch intent for $packageName")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to relaunch $packageName", t)
        }
    }

    private fun completeOp(success: Boolean, error: String?) {
        cancelWatchdog()
        stopPolling()
        val op = pendingOp ?: return
        pendingOp = null
        opState = OpState.IDLE
        settingsWindowId = WINDOW_ID_UNSET
        dialogWindowId = WINDOW_ID_UNSET
        try {
            op.callback(success, error)
        } catch (t: Throwable) {
            Log.e(TAG, "callback threw", t)
        }
    }

    // endregion

    // region — node finding

    private fun findForceStopNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1) AOSP-style resource id
        for (id in FORCE_STOP_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                Log.d(TAG, "force-stop found by id=$id")
                return nodes[0]
            }
        }
        // 2) Localised label — match exact text on clickable nodes or their nearest clickable ancestor.
        //    Samsung One UI exposes "Force stop" as the text of a button inside a bottom action bar
        //    whose immediate node is not clickable; we promote to the clickable ancestor below.
        for (text in FORCE_STOP_TEXTS) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                // Skip the dialog title — it has the same text but isn't in the App info screen.
                if (node.windowId != settingsWindowId && settingsWindowId != WINDOW_ID_UNSET) continue
                val candidate = if (node.isClickable) node else firstClickableAncestor(node)
                if (candidate != null) {
                    Log.d(TAG, "force-stop found by text='$text'")
                    return candidate
                }
            }
        }
        return null
    }

    /**
     * Returns the root of the dialog window (i.e. a window different from the
     * settings activity window). Returns null while we're still waiting for it.
     */
    private fun findDialogRoot(): AccessibilityNodeInfo? {
        val all = try { windows } catch (_: Throwable) { null } ?: return null
        for (win in all) {
            if (win.id == settingsWindowId) continue
            if (win.type == AccessibilityWindowInfo.TYPE_SYSTEM) continue
            if (win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = try { win.root } catch (_: Throwable) { null } ?: continue
            if (root.packageName != SETTINGS_PACKAGE) continue
            dialogWindowId = win.id
            Log.d(TAG, "Dialog window id=${win.id} type=${win.type} root.class=${root.className}")
            return root
        }
        return null
    }

    private fun findDialogPositiveButton(dialogRoot: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1) Standard AlertDialog positive button.
        val byId = dialogRoot.findAccessibilityNodeInfosByViewId("android:id/button1")
        if (byId.isNotEmpty()) {
            Log.d(TAG, "OK found by id=android:id/button1")
            return byId[0]
        }
        // 2) Match by exact button text — but never "Force stop" (that's the dialog title on Samsung).
        for (text in OK_BUTTON_TEXTS_STRICT) {
            val nodes = dialogRoot.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                val candidate = if (node.isClickable) node else firstClickableAncestor(node)
                if (candidate != null) {
                    Log.d(TAG, "OK found by text='$text'")
                    return candidate
                }
            }
        }
        // 3) Last-resort: rightmost / last clickable Button-class node inside the dialog.
        val lastButton = lastClickableButton(dialogRoot)
        if (lastButton != null) {
            Log.d(TAG, "OK fallback: last button class=${lastButton.className} text=${lastButton.text}")
        }
        return lastButton
    }

    private fun firstClickableAncestor(start: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = start?.parent
        var depth = 0
        while (n != null && depth < 8) {
            if (n.isClickable) return n
            n = n.parent
            depth++
        }
        return null
    }

    private fun lastClickableButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val cls = n.className?.toString().orEmpty()
            val looksLikeButton = cls.contains("Button", ignoreCase = true)
            if (n.isClickable && looksLikeButton) {
                // Prefer later-found buttons (positive is usually rightmost / last).
                result = n
            }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (_: Throwable) { null } ?: continue
                stack.addLast(c)
            }
        }
        return result
    }

    private fun safeRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (t: Throwable) {
        Log.w(TAG, "rootInActiveWindow threw", t)
        null
    }

    private fun armWatchdog(ms: Long) {
        cancelWatchdog()
        handler.postDelayed(watchdog, ms)
    }

    private fun cancelWatchdog() {
        handler.removeCallbacks(watchdog)
    }

    // endregion

    private data class KillOp(
        val targetPackage: String,
        val relaunchAfter: Boolean,
        val callback: (Boolean, String?) -> Unit,
        val appLabel: String? = null
    )

    private enum class OpState {
        IDLE,
        // Settings flow
        OPENING_SETTINGS,
        CLICKING_FORCE_STOP,
        CONFIRMING,
        // Recents flow
        OPENING_RECENTS,
        DISMISSING_CARD,
        // Shared
        RETURNING
    }

    companion object {
        private const val TAG = "FloatKill.FGDetector"
        private const val SETTINGS_PACKAGE = "com.android.settings"

        private const val WINDOW_ID_UNSET = -1

        // Candidate resource IDs for the "Force stop" entry point on the App info screen.
        private val FORCE_STOP_IDS = listOf(
            "com.android.settings:id/force_stop_button",
            "com.android.settings:id/right_button",
            "com.android.settings:id/force_stop"
        )

        // Localised force-stop labels.
        private val FORCE_STOP_TEXTS = listOf(
            "Force stop",
            "Termina",
            "Forza arresto",
            "Arresto forzato",
            "Forzar detención",
            "Forcer l'arrêt",
            "Beenden erzwingen",
            "Stop forçado",
            "强行停止",
            "強制停止",
            "강제 중지"
        )

        // Strict candidates for the dialog's positive button — must NOT contain
        // "Force stop" or its translations, because those collide with the dialog
        // title text on Samsung devices.
        private val OK_BUTTON_TEXTS_STRICT = listOf(
            "OK", "Ok",
            "Conferma", "Confirm", "Confirmer", "Bestätigen", "Aceptar",
            "确定", "确认", "確認"
        )

        // Labels of the "close this Recents card" custom action across OEMs.
        // Samsung One UI labels it "Close"; localised builds use the translated form.
        private val CLOSE_LABELS = listOf(
            "Close",
            "Chiudi",
            "Cerrar",
            "Fermer",
            "Schließen",
            "Fechar",
            "关闭",
            "閉じる",
            "닫기",
            "Закрыть"
        )

        // AOSP / LG UX expose dismiss as a separate child button under the card.
        private val DISMISS_BUTTON_ID_SUFFIXES = listOf(
            ":id/dismiss_task",
            ":id/dismiss"
        )

        private val DISMISS_BUTTON_CD_LABELS = listOf(
            "Dismiss",
            "Ignora",          // Italian
            "Close",
            "Chiudi",
            "Cerrar",
            "Fermer",
            "Schließen",
            "Fechar",
            "관리 닫기",
            "닫기",
            "关闭",
            "閉じる",
            "Закрыть"
        )

        private const val WATCHDOG_MS = 8000L
        private const val POLL_INTERVAL_MS = 200L
        private const val BACK_INTERVAL_MS = 250L
        private const val RELAUNCH_DELAY_MS = 350L
        private const val MAX_ATTEMPTS = 60
        private const val DEBOUNCE_AFTER_CLICK_MS = 350L
        private const val DUMP_AFTER_ATTEMPTS = 8

        @Volatile
        var currentPackage: String? = null
            private set

        @Volatile
        var connected: Boolean = false
            private set

        @Volatile
        private var INSTANCE: ForegroundDetectorService? = null

        fun instance(): ForegroundDetectorService? = INSTANCE
    }
}
