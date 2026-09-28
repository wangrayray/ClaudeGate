package tw.claudegate.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.claudegate.app.Ui.dp

/**
 * 整個 App 的核心：
 *  1. 偵測前景 App 與瀏覽器網址；被鎖且沒有有效解鎖 → 跳到 GateActivity。
 *  2. 解鎖時間到 → 再跳一次 GateActivity（「時間到」）。
 *  3. 解鎖期間／專注模式下，定期把畫面文字交給 AI 判斷有沒有離題，離題就在畫面上方提醒。
 */
class GateAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: Settings
    private lateinit var judge: Judge

    /** 不算「換了前景」的套件：系統列、輸入法 */
    private var ignored: Set<String> = emptySet()

    private var fgPkg: String? = null
    private var fgUrl: String? = null
    private var fgHost: String? = null
    private var urlChangedAt = 0L
    private var lastScanAt = 0L

    private var lastGateKey: String? = null
    private var lastGateAt = 0L

    // 離題偵測狀態
    private var sessionKey: String? = null
    private var lastCheckAt = 0L
    private var lastCheckedUrl: String? = null
    private var lastCheckedHash = 0
    private var checking: Job? = null
    private var offTaskStreak = 0
    private var warnedExpiry: String? = null

    private var overlay: View? = null
    private val dismissOverlay = Runnable { removeOverlay() }

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = Settings(this)
        judge = Judge(this)
        val imes = try {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        } catch (e: Exception) {
            emptyList()
        }
        ignored = setOf("com.android.systemui") + imes
        main.removeCallbacks(tick)
        main.post(tick)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (!::settings.isInitialized) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refresh(force = true)
            // 網址列的變化是 content changed；只有瀏覽器需要看，而且要節流
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (Browsers.isBrowser(e.packageName?.toString())) refresh(force = false)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 偵測與封鎖

    private fun refresh(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastScanAt < SCAN_THROTTLE_MS) return
        lastScanAt = now
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg in ignored) return

        if (pkg != fgPkg) {
            // 解鎖畫面已經出現：去抖動只是為了避免畫面出現前重複開，
            // 出現後就清掉，按「算了」再馬上打開被鎖的 App 也會立刻被擋
            if (pkg == packageName) lastGateKey = null
            fgPkg = pkg
            fgUrl = null
            fgHost = null
            onForegroundChanged()
        }
        if (Browsers.isBrowser(pkg)) {
            Browsers.readUrl(root, pkg)?.let { url ->
                if (url != fgUrl) {
                    fgUrl = url
                    fgHost = Browsers.hostOf(url)
                    urlChangedAt = now
                }
            }
        }
        enforce(now)
    }

    private fun enforce(now: Long) {
        val pkg = fgPkg ?: return
        if (pkg == packageName) return

        if (pkg in settings.lockedApps) {
            val key = Target.App.keyOf(pkg)
            if (Grants.active(this, key, now) == null) {
                block(Target.App(pkg), recentlyExpired(key, now))
                return
            }
        }
        if (Browsers.isBrowser(pkg)) {
            val host = fgHost ?: return
            val domain = Browsers.matchDomain(host, settings.lockedSites) ?: return
            val key = Target.Site.keyOf(domain)
            if (Grants.active(this, key, now) == null) {
                block(Target.Site(domain, fgUrl ?: domain, pkg), recentlyExpired(key, now))
            }
        }
    }

    /** 十分鐘內才到期的解鎖 → 顯示「時間到」而不是「想打開？」 */
    private fun recentlyExpired(key: String, now: Long): Boolean {
        val g = Grants.latest(this, key) ?: return false
        return g.until <= now && now - g.until < 10 * 60_000L
    }

    private fun block(t: Target, expired: Boolean) {
        val now = System.currentTimeMillis()
        if (t.key == lastGateKey && now - lastGateAt < GATE_DEBOUNCE_MS) return
        lastGateKey = t.key
        lastGateAt = now
        removeOverlay()
        GateActivity.launch(this, t, expired)
    }

    private fun onForegroundChanged() {
        sessionKey = null
        offTaskStreak = 0
        removeOverlay()
    }

    /** 目前前景對應的有效解鎖（被鎖的 App，或瀏覽器裡被鎖的網站） */
    private fun currentGrant(now: Long): Grant? {
        val pkg = fgPkg ?: return null
        if (pkg in settings.lockedApps) Grants.active(this, Target.App.keyOf(pkg), now)?.let { return it }
        if (Browsers.isBrowser(pkg)) {
            val d = fgHost?.let { Browsers.matchDomain(it, settings.lockedSites) } ?: return null
            return Grants.active(this, Target.Site.keyOf(d), now)
        }
        return null
    }

    // ---------------------------------------------------------------- 定時工作

    private fun onTick() {
        if (!::settings.isInitialized) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return
        refresh(force = true)
        val now = System.currentTimeMillis()
        warnBeforeExpiry(now)
        monitor(now)
    }

    private fun warnBeforeExpiry(now: Long) {
        val g = currentGrant(now) ?: return
        val left = g.remainingMs(now)
        val tag = "${g.key}@${g.until}"
        if (left in 1..60_000L && warnedExpiry != tag && overlay == null) {
            warnedExpiry = tag
            showOverlay("還剩 1 分鐘", "${g.label} 快到時間了，把手上的事收個尾吧。",
                listOf("好" to { removeOverlay() }), 8_000L)
        }
    }

    /** 離題偵測 */
    private fun monitor(now: Long) {
        if (checking?.isActive == true) return
        if (settings.apiKey(settings.provider(Role.MONITOR)).isBlank()) return
        val pkg = fgPkg ?: return
        if (pkg == packageName) return
        val grant = currentGrant(now)

        val purpose: String
        val key: String
        val label: String
        if (grant != null && grant.purpose.isNotBlank() && settings.monitorEnabled) {
            purpose = grant.purpose
            key = "${grant.key}@${grant.grantedAt}"
            label = grant.label
        } else if (settings.focusActive(now) && Browsers.isBrowser(pkg)) {
            purpose = "（專注模式）${settings.focusGoal}"
            key = "focus@${settings.focusUntil}"
            label = Target.appLabel(this, pkg)
        } else {
            sessionKey = null
            return
        }

        if (key != sessionKey) {
            // 新的一段使用：先給一點時間，不要一打開就查
            sessionKey = key
            lastCheckAt = now - settings.monitorIntervalSec * 1000L + FIRST_CHECK_DELAY_MS
            lastCheckedHash = 0
            lastCheckedUrl = null
            offTaskStreak = 0
            return
        }

        val interval = settings.monitorIntervalSec * 1000L
        val urlChanged = fgUrl != null && fgUrl != lastCheckedUrl && now - urlChangedAt >= URL_SETTLE_MS
        val due = now - lastCheckAt >= interval || (urlChanged && now - lastCheckAt >= MIN_GAP_MS)
        if (!due) return

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val screen = ScreenText.collect(root)
        val url = fgUrl
        val hash = (screen + url).hashCode()
        lastCheckAt = now
        if (hash == lastCheckedHash) return   // 畫面沒變就不重問
        lastCheckedHash = hash
        lastCheckedUrl = url

        val usedMin = grant?.let { ((now - it.grantedAt) / 60_000L).toInt() }
        val leftMin = grant?.let { (it.remainingMs(now) / 60_000L).toInt() }
        checking = scope.launch {
            val r = try {
                withContext(Dispatchers.IO) {
                    judge.checkRelevance(purpose, label, url, usedMin, leftMin, screen)
                }
            } catch (e: AiException) {
                Log.w(TAG, "relevance check failed: ${e.message}")
                return@launch
            }
            if (sessionKey != key) return@launch   // 查的期間已經換到別的 App
            if (r.onTask) {
                offTaskStreak = 0
            } else {
                offTaskStreak++
                showReminder(r.reminder, grant, offTaskStreak)
            }
        }
    }

    // ---------------------------------------------------------------- 畫面上方的提醒

    private fun showReminder(msg: String, grant: Grant?, streak: Int) {
        val name = settings.provider(Role.MONITOR).displayName
        val title = if (streak >= 2) "$name 提醒（連續第 $streak 次）" else "$name 提醒"
        val body = msg.ifBlank { "這個畫面好像跟你原本要做的事沒什麼關係。" }
        val actions = mutableListOf<Pair<String, () -> Unit>>("回到正事" to { removeOverlay() })
        if (grant != null) {
            actions += "結束使用" to {
                Grants.end(this, grant.key)
                removeOverlay()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        } else {
            actions += "離開這頁" to {
                removeOverlay()
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
        showOverlay(title, body, actions, 30_000L)
    }

    private fun showOverlay(title: String, body: String, actions: List<Pair<String, () -> Unit>>, autoDismissMs: Long) {
        removeOverlay()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val card = Ui.column(this, 14).apply {
            background = Ui.rounded(this@GateAccessibilityService, 0xF2302C28.toInt(), 16)
            elevation = dp(8).toFloat()
        }
        card.addView(Ui.text(this, title, 14f, Ui.ACCENT, bold = true))
        card.addView(Ui.text(this, body, 16f).apply { setPadding(0, dp(4), 0, dp(6)) })
        val row = Ui.row(this)
        actions.forEachIndexed { i, (label, fn) ->
            row.addView(Ui.button(this, label, primary = i == 0) { fn() },
                LinearLayout.LayoutParams(0, Ui.wrap(), 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        card.addView(row)
        val frame = FrameLayout(this).apply {
            setPadding(dp(12), 0, dp(12), 0)
            addView(card)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = dp(56)
        }
        try {
            wm.addView(frame, lp)
            overlay = frame
            main.removeCallbacks(dismissOverlay)
            main.postDelayed(dismissOverlay, autoDismissMs)
        } catch (e: Exception) {
            Log.w(TAG, "overlay failed", e)
        }
    }

    private fun removeOverlay() {
        main.removeCallbacks(dismissOverlay)
        val v = overlay ?: return
        overlay = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "remove overlay failed", e)
        }
    }

    companion object {
        private const val TAG = "ClaudeGate"
        private const val TICK_MS = 5_000L
        private const val SCAN_THROTTLE_MS = 600L
        private const val GATE_DEBOUNCE_MS = 2_500L
        /** 網址變了之後等頁面穩定再查 */
        private const val URL_SETTLE_MS = 8_000L
        /** 換頁觸發的檢查，兩次之間至少隔這麼久 */
        private const val MIN_GAP_MS = 20_000L
        /** 解鎖後第一次檢查的延遲 */
        private const val FIRST_CHECK_DELAY_MS = 30_000L

        fun isEnabled(ctx: Context): Boolean {
            val enabled = SysSettings.Secure.getString(
                ctx.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, GateAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** 把畫面上看得到的文字抓下來（給 AI 判斷是否離題用）。 */
object ScreenText {
    private const val MAX_CHARS = 2500
    private const val MAX_NODES = 600

    fun collect(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val seen = HashSet<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES && sb.length < MAX_CHARS) {
            val n = stack.removeLast()
            visited++
            if (!n.isVisibleToUser) continue
            if (!n.isPassword) {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()
                if (t != null && t.length > 1 && seen.add(t)) sb.append(t.take(300)).append('\n')
            }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it) }
        }
        return sb.toString().take(MAX_CHARS)
    }
}
