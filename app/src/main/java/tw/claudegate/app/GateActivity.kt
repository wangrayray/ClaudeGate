package tw.claudegate.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.claudegate.app.Ui.dp

/**
 * 解鎖對話：打開被鎖的 App／網站時跳到這裡，跟 AI（Claude 或 Gemini）說明理由，
 * AI 同意（approve）才會記下一筆 Grant 並打開目標。
 */
class GateActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: Settings
    private lateinit var judge: Judge

    private var target: Target? = null
    private var expired = false
    private val history = mutableListOf<Turn>()
    private var job: Job? = null

    private lateinit var icon: ImageView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var scroll: ScrollView
    private lateinit var chat: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var inputRow: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var openBtn: Button
    private lateinit var emergencyBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        judge = Judge(this)
        buildUi()
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val t = Target.from(intent)
        // 同一個目標、對話進行中：保留對話；換了目標就重來
        if (t != target || intent.getBooleanExtra(EXTRA_EXPIRED, false) != expired) load(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 返回鍵不能回到被鎖的 App，一律回桌面
        goHome()
    }

    // ---------------------------------------------------------------- 畫面

    private fun buildUi() {
        val root = Ui.column(this, 16)
        root.setBackgroundColor(Ui.BG)

        val header = Ui.row(this)
        icon = ImageView(this)
        header.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = Ui.column(this)
        title = Ui.text(this, "", 20f, bold = true)
        subtitle = Ui.text(this, "", 13f, Ui.MUTED)
        titles.addView(title)
        titles.addView(subtitle)
        header.addView(titles, LinearLayout.LayoutParams(0, Ui.wrap(), 1f).apply { marginStart = dp(12) })
        root.addView(header)

        scroll = ScrollView(this)
        chat = Ui.column(this)
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(Ui.match(), 0, 1f).apply { topMargin = dp(12) })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress)

        openBtn = Ui.button(this, "") { approvedOpen() }.apply { visibility = View.GONE }
        root.addView(openBtn)

        emergencyBtn = Ui.button(this, "", primary = false) { useEmergencyPass() }
            .apply { visibility = View.GONE }
        root.addView(emergencyBtn)

        inputRow = Ui.row(this).apply { gravity = Gravity.BOTTOM }
        input = Ui.edit(this, "說說你為什麼想打開…", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply { maxLines = 5 }
        send = Ui.button(this, "送出") { onSend() }
        inputRow.addView(input, LinearLayout.LayoutParams(0, Ui.wrap(), 1f))
        inputRow.addView(send, LinearLayout.LayoutParams(Ui.wrap(), Ui.wrap()).apply { marginStart = dp(8) })
        root.addView(inputRow)

        root.addView(Ui.button(this, "算了，不打開", primary = false) { goHome() })
        setContentView(root)
    }

    private fun bubble(text: String, fromUser: Boolean, color: Int? = null) {
        val tv = Ui.text(this, text, 15.5f).apply {
            background = Ui.rounded(this@GateActivity,
                color ?: if (fromUser) Ui.BUBBLE_ME else Ui.BUBBLE_CLAUDE)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
            setTextIsSelectable(true)
        }
        chat.addView(tv, LinearLayout.LayoutParams(Ui.wrap(), Ui.wrap()).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = dp(8)
        })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setBusy(b: Boolean) {
        progress.visibility = if (b) View.VISIBLE else View.GONE
        send.isEnabled = !b
        input.isEnabled = !b
    }

    // ---------------------------------------------------------------- 流程

    private fun load(i: Intent) {
        val t = Target.from(i) ?: run { finish(); return }
        job?.cancel()
        target = t
        expired = i.getBooleanExtra(EXTRA_EXPIRED, false)
        history.clear()
        chat.removeAllViews()
        setBusy(false)
        openBtn.visibility = View.GONE
        emergencyBtn.visibility = View.GONE
        inputRow.visibility = View.VISIBLE
        input.setText("")

        val label = t.label(this)
        icon.setImageDrawable(t.icon(this))
        title.text = if (expired) "$label 的時間到了" else "想打開 $label？"
        val day = UsageLog.get(this, t.key)
        subtitle.text = buildString {
            append("今天已解鎖 ${day.approved} 次，共 ${day.minutes} 分鐘")
            if (day.denied > 0) append("；被拒絕 ${day.denied} 次")
        }

        bubble(
            if (expired) "時間到囉。事情做完了嗎？如果還需要時間，跟我說還差什麼。"
            else "要打開 $label 做什麼呢？說得越具體（找誰、查什麼、要多久），越容易通過。",
            fromUser = false,
        )
        val provider = settings.provider(Role.GATE)
        input.hint = "跟 ${provider.displayName} 說你為什麼想打開…"
        if (settings.apiKey(provider).isBlank()) {
            bubble("還沒設定 ${provider.displayName} 的 API 金鑰，我沒辦法判斷。請先到「${getString(R.string.app_name)}」設定頁填入金鑰。",
                fromUser = false, color = 0xFF5A3A2A.toInt())
            openBtn.text = "前往設定"
            openBtn.setOnClickListener {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            openBtn.visibility = View.VISIBLE
            inputRow.visibility = View.GONE
            showEmergencyIfAllowed()
        }
    }

    private fun onSend() {
        val t = target ?: return
        val msg = input.text.toString().trim()
        if (msg.isEmpty()) return
        input.setText("")
        bubble(msg, fromUser = true)
        history.add(Turn(fromUser = true, text = msg))
        setBusy(true)

        job = scope.launch {
            try {
                val d = withContext(Dispatchers.IO) {
                    judge.decide(t, expired, history.toList())
                }
                // 回覆原文（JSON）放回歷史，下一輪 AI 才看得到自己說過什麼
                history.add(Turn(fromUser = false, text = d.raw))
                bubble(d.reply, fromUser = false)
                when (d.kind) {
                    Judge.Kind.APPROVE -> onApproved(t, d)
                    Judge.Kind.DENY -> UsageLog.record(this@GateActivity, t.key, approved = false, minutes = 0)
                    Judge.Kind.ASK -> Unit
                }
            } catch (e: AiException) {
                // 這一輪沒有成功：把使用者那句拿掉，讓他可以重送
                if (history.lastOrNull()?.fromUser == true) history.removeAt(history.lastIndex)
                bubble("⚠️ ${e.message}", fromUser = false, color = 0xFF5A3A2A.toInt())
                input.setText(msg)
                showEmergencyIfAllowed()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun onApproved(t: Target, d: Judge.Decision) {
        val now = System.currentTimeMillis()
        val g = Grant(t.key, t.label(this), d.purpose, now, now + d.minutes * 60_000L)
        Grants.put(this, g)
        UsageLog.record(this, t.key, approved = true, minutes = d.minutes)
        inputRow.visibility = View.GONE
        emergencyBtn.visibility = View.GONE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        openBtn.text = "打開 ${g.label}（${d.minutes} 分鐘）"
        openBtn.setOnClickListener { approvedOpen() }
        openBtn.visibility = View.VISIBLE
    }

    private fun approvedOpen() {
        val t = target ?: return
        if (Grants.active(this, t.key) == null) return
        openTarget(t)
    }

    /** API 連不上時的逃生口：每天有限次數，時間很短，會記錄下來讓 AI 之後看到 */
    private fun showEmergencyIfAllowed() {
        val left = EMERGENCY_PER_DAY - UsageLog.totalEmergencyToday(this)
        if (left <= 0) return
        emergencyBtn.text = "緊急通行 $EMERGENCY_MINUTES 分鐘（今天還剩 $left 次）"
        emergencyBtn.visibility = View.VISIBLE
    }

    private fun useEmergencyPass() {
        val t = target ?: return
        if (UsageLog.totalEmergencyToday(this) >= EMERGENCY_PER_DAY) return
        val now = System.currentTimeMillis()
        Grants.put(this, Grant(t.key, t.label(this), "", now, now + EMERGENCY_MINUTES * 60_000L))
        UsageLog.record(this, t.key, approved = true, minutes = EMERGENCY_MINUTES, emergency = true)
        openTarget(t)
    }

    private fun openTarget(t: Target) {
        val launch: Intent? = when (t) {
            is Target.App -> packageManager.getLaunchIntentForPackage(t.pkg)
            // 回到原本的瀏覽器分頁（頁面還在），沒有記到瀏覽器就用網址開
            is Target.Site -> t.browserPkg?.let { packageManager.getLaunchIntentForPackage(it) }
                ?: Intent(Intent.ACTION_VIEW, Uri.parse(Browsers.toOpenableUrl(t.url)))
        }
        try {
            launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { startActivity(it) }
        } catch (e: ActivityNotFoundException) {
            bubble("找不到可以打開 ${t.label(this)} 的 App。", fromUser = false)
            return
        }
        finish()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        private const val EXTRA_EXPIRED = "expired"
        const val EMERGENCY_PER_DAY = 2
        const val EMERGENCY_MINUTES = 5

        fun launch(ctx: Context, target: Target, expired: Boolean) {
            val i = target.putInto(Intent(ctx, GateActivity::class.java))
                .putExtra(EXTRA_EXPIRED, expired)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            ctx.startActivity(i)
        }
    }
}
