package com.maira.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.app.Notification
import android.graphics.Rect
import android.os.Handler
import android.os.Looper

class MairaAccessibilityService : AccessibilityService() {

    private val h = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected(); instance = this
        // Bound by the system at boot: a good moment to bring "Hey Maira" back (best effort on Android 14+).
        h.postDelayed({ try { Watchdog.ensureWake(this) } catch (_: Exception) {} }, 3000)
        // If Auto Setup sent the user here to turn this on, go straight back to the wizard.
        if (Prefs.guideStep(this) == "a11y" || Prefs.guideStep(this) == "restricted") {
            Prefs.setGuideStep(this, null)
            h.postDelayed({ AutoSetupActivity.bringToFront(this) }, 700)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) { onWindowEvent(event); return }
        try {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return
            val n = event.parcelableData as? Notification ?: return
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
            val ex = n.extras
            val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (ex?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_TEXT))?.toString()
                ?: event.text.joinToString(" ")
            if (title.isBlank() && text.isBlank()) return
            synchronized(notifCache) {
                notifCache.addFirst(NotifItem(appLabel(pkg), title, text, System.currentTimeMillis()))
                while (notifCache.size > 15) notifCache.removeLast()
            }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ v1.2: auto-click, setup guide, screen bolo
    private var lastClickAt = 0L
    private var clicksLeft = 12
    private var lastHint: String? = null
    private var lastHintAt = 0L
    private var boloPkg = ""
    private var lastBoloKey = ""

    private fun onWindowEvent(e: AccessibilityEvent) {
        val type = e.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        try {
            if (AutoClick.isInstallerPkg(pkg) && Prefs.updateArmed(this)) autoClick(pkg, installer = true)
            else if (AutoClick.isPermissionPkg(pkg) && Prefs.permArmed(this)) autoClick(pkg, installer = false)
        } catch (_: Exception) {}
        if (Prefs.guideStep(this) != null) { h.removeCallbacks(guideRun); h.postDelayed(guideRun, 1200) }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && Prefs.screenBolo(this)) {
            boloPkg = pkg; h.removeCallbacks(boloRun); h.postDelayed(boloRun, 2000)
        }
    }

    /** Flattens visible nodes of the top-most non-Maira windows (optionally only [pkg]). */
    private fun snapshot(pkg: String?, maxNodes: Int = 600): List<Pair<UiNode, AccessibilityNodeInfo>> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows.orEmpty().sortedByDescending { it.layer }) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION && w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
                val r = w.root ?: continue
                val rp = r.packageName?.toString() ?: continue
                if (rp == packageName || rp == "com.android.systemui") continue
                if (pkg != null && rp != pkg) continue
                roots.add(r)
                if (roots.size >= 2) break
            }
        } catch (_: Exception) {}
        if (roots.isEmpty()) rootInActiveWindow?.takeIf { val rp = it.packageName?.toString(); rp != packageName && (pkg == null || rp == pkg) }?.let { roots.add(it) }
        val out = ArrayList<Pair<UiNode, AccessibilityNodeInfo>>()
        for (root in roots) {
            val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.addLast(root)
            while (stack.isNotEmpty() && out.size < maxNodes) {
                val n = stack.removeFirst()
                if (!n.isVisibleToUser) continue
                if (!n.isPassword) out.add(UiNode(n.text?.toString().orEmpty(), n.contentDescription?.toString().orEmpty(), n.viewIdResourceName.orEmpty(),
                    n.isClickable, n.isCheckable, n.isChecked, n.isEnabled, n.className?.toString().orEmpty()) to n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return out
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable && cur.isEnabled) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
        }
        return false
    }

    /** Scoped auto-tap: only Maira's own update in the package installer, or Maira's own permission dialog during setup. */
    private fun autoClick(pkg: String, installer: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastClickAt < 700) return
        if (clicksLeft <= 0) { if (now - lastClickAt > 10 * 60_000L) clicksLeft = 12 else return }
        val snap = snapshot(pkg)
        val nodes = snap.map { it.first }
        val idx = if (installer) AutoClick.installerTarget(pkg, nodes) else AutoClick.permissionTarget(pkg, nodes)
        if (idx != null && clickNode(snap[idx].second)) { lastClickAt = now; clicksLeft-- }
    }

    private val guideRun = Runnable {
        val step = Prefs.guideStep(this) ?: return@Runnable
        if (WakeWordService.uiActive && rootInActiveWindow?.packageName?.toString() == packageName) return@Runnable
        val hint = try { SetupGuide.hint(step, snapshot(null).map { it.first }) } catch (_: Exception) { null } ?: return@Runnable
        val now = System.currentTimeMillis()
        if (hint == lastHint && now - lastHintAt < 25_000) return@Runnable
        lastHint = hint; lastHintAt = now
        try { MairaApp.instance.tts.speak(hint) } catch (_: Exception) {}
    }

    private fun keyboardPkg(): String = try {
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/') ?: ""
    } catch (_: Exception) { "" }

    private var boloRetry = 0
    private val boloRun = object : Runnable {
        override fun run() {
            if (!Prefs.screenBolo(this@MairaAccessibilityService)) return
            if (WakeWordService.uiActive) return
            val app = MairaApp.instance
            if (app.tts.isSpeaking()) { if (boloRetry++ < 3) h.postDelayed(this, 2000); return }
            boloRetry = 0
            val pkg = boloPkg
            if (ScreenBolo.shouldSkip(pkg, packageName, appLabel(pkg), pkg == keyboardPkg())) return
            val cap = try { readScreen() } catch (_: Exception) { null } ?: return
            if (ScreenBolo.shouldSkip(cap.pkg, packageName, cap.appName, cap.pkg == keyboardPkg())) return
            val key = cap.pkg + "|" + cap.text.take(120)
            if (key == lastBoloKey) return
            lastBoloKey = key
            app.tts.speak(ScreenBolo.brief(cap.appName, cap.text))
        }
    }

    fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    /**
     * Reads visible text of the app(s) on screen, skipping Maira's own windows, the keyboard and system bars.
     * Call after Maira has hidden itself.
     */
    fun readScreen(): ScreenCapture? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            val ws = windows.orEmpty().sortedByDescending { it.layer }
            for (w in ws) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                if (r.packageName?.toString() == packageName) continue
                roots.add(r)
            }
            if (roots.isEmpty()) {
                // e.g. notification shade pulled down: read system windows too
                for (w in ws) {
                    if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
                    val r = w.root ?: continue
                    if (r.packageName?.toString() == packageName) continue
                    roots.add(r)
                }
            }
        } catch (_: Exception) {}
        if (roots.isEmpty()) rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }?.let { roots.add(it) }
        if (roots.isEmpty()) return null
        val items = ArrayList<ScreenItem>()
        val rect = Rect()
        var budget = 2500
        for ((g, root) in roots.withIndex()) {
            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            stack.addLast(root to 0)
            while (stack.isNotEmpty() && budget-- > 0) {
                val (n, depth) = stack.removeLast()
                if (!n.isVisibleToUser) continue
                n.getBoundsInScreen(rect)
                val t = n.text?.toString()
                val d = n.contentDescription?.toString()
                val isPwd = n.isPassword
                if (!isPwd && !t.isNullOrBlank() && !(n.isEditable && n.isShowingHintText)) items.add(ScreenItem(t, rect.top, rect.left, g))
                if (!d.isNullOrBlank() && d != t) items.add(ScreenItem(d, rect.top, rect.left, g))
                if (depth < 60) for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it to depth + 1) }
            }
        }
        val pkg = roots.first().packageName?.toString() ?: ""
        return ScreenCapture(appLabel(pkg), pkg, ScreenText.compose(items))
    }

    fun recentNotifications(): List<NotifItem> = synchronized(notifCache) { notifCache.toList() }
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun global(action: Int): Boolean = performGlobalAction(action)

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
        return null
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: findEditable(root) ?: return false
        val existing = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        val newText = if (existing.isBlank()) text else "$existing $text"
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText) }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(n)
        while (q.isNotEmpty()) {
            val c = q.removeFirst()
            if (c.isScrollable) return c
            for (i in 0 until c.childCount) c.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    fun scroll(down: Boolean): Boolean {
        val node = findScrollable(rootInActiveWindow)
        if (node != null) {
            val ok = node.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            if (ok) return true
        }
        // Fallback: swipe gesture
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val y1 = dm.heightPixels * if (down) 0.75f else 0.3f
        val y2 = dm.heightPixels * if (down) 0.3f else 0.75f
        val p = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 350)).build()
        return dispatchGesture(g, null, null)
    }

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text) ?: return false
        for (n in nodes) {
            var cur: AccessibilityNodeInfo? = n
            while (cur != null) {
                if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                cur = cur.parent
            }
        }
        return false
    }

    companion object {
        @Volatile var instance: MairaAccessibilityService? = null
        private val notifCache = ArrayDeque<NotifItem>()

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, MairaAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
