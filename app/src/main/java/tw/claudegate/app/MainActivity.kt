package tw.claudegate.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import tw.claudegate.app.Ui.dp

/** 設定頁：權限、API 金鑰、要鎖的 App／網站、專注模式、給 AI 的規則、目前解鎖中的項目。 */
class MainActivity : Activity() {

    private lateinit var settings: Settings

    private lateinit var serviceStatus: TextView
    private lateinit var claudeKey: EditText
    private lateinit var geminiKey: EditText
    /** 每個用途目前選的供應商（還沒存）與模型欄位 */
    private val chosen = HashMap<Role, Provider>()
    private val modelFields = HashMap<Role, EditText>()
    private lateinit var appsSummary: TextView
    private lateinit var siteList: LinearLayout
    private lateinit var newSite: EditText
    private lateinit var focusGoal: EditText
    private lateinit var focusMinutes: EditText
    private lateinit var focusStatus: TextView
    private lateinit var focusBtn: View
    private lateinit var rules: EditText
    private lateinit var maxMinutes: EditText
    private lateinit var monitorEnabled: CheckBox
    private lateinit var monitorInterval: EditText
    private lateinit var grantList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        // 離開頁面時自動存，避免使用者忘了按儲存
        save(quiet = true)
        super.onPause()
    }

    private fun buildUi() {
        val c = Ui.column(this, 16)
        val add = { v: View -> c.addView(v) }

        add(Ui.text(this, getString(R.string.app_name), 24f, bold = true))
        add(Ui.note(this, "被鎖住的 App 或網站要先跟 AI（Claude 或 Gemini）說明理由，同意後才能用；使用中如果離題，會在畫面上方提醒你。"))

        // 1. 權限
        add(Ui.section(this, "1. 無障礙權限"))
        serviceStatus = Ui.text(this, "")
        add(serviceStatus)
        add(Ui.note(this, "需要「無障礙服務」才能知道你打開了哪個 App、瀏覽器在哪個網址，以及在畫面上顯示提醒。"))
        add(Ui.button(this, "開啟無障礙設定") {
            startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        // 2. AI
        add(Ui.section(this, "2. AI 設定"))
        add(Ui.note(this, "解鎖對話和離題偵測可以各自選 Claude 或 Gemini，例如解鎖用 Claude、離題偵測用有免費額度的 Gemini。"))
        add(Ui.text(this, "Claude API 金鑰（console.anthropic.com）", 14f))
        claudeKey = Ui.edit(this, "sk-ant-…", settings.claudeKey,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        add(claudeKey)
        add(Ui.text(this, "Gemini API 金鑰（aistudio.google.com，有免費額度）", 14f))
        geminiKey = Ui.edit(this, "AIza…", settings.geminiKey,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        add(geminiKey)
        add(Ui.note(this, "⚠️ Gemini 免費版送出的內容（包含離題偵測抓到的畫面文字）可能被 Google 用來改進產品，也可能有人工審閱。金鑰只存在這支手機的 App 私有空間。"))
        addProviderPicker(c, Role.GATE, "解鎖對話用")
        addProviderPicker(c, Role.MONITOR, "離題偵測用（會定期呼叫，建議用便宜或免費的模型）")

        // 3. App
        add(Ui.section(this, "3. 要鎖住的 App"))
        appsSummary = Ui.text(this, "")
        add(appsSummary)
        add(Ui.button(this, "選擇 App") {
            startActivity(Intent(this, AppPickerActivity::class.java))
        })

        // 4. 網站
        add(Ui.section(this, "4. 要鎖住的網站"))
        add(Ui.note(this, "支援 Chrome、Samsung 網際網路、Firefox、Edge、Brave、Opera、Vivaldi、DuckDuckGo。輸入網域，子網域會一起鎖（鎖 youtube.com 也會鎖 m.youtube.com）。"))
        siteList = Ui.column(this)
        add(siteList)
        val siteRow = Ui.row(this)
        newSite = Ui.edit(this, "例如 youtube.com", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        siteRow.addView(newSite, LinearLayout.LayoutParams(0, Ui.wrap(), 1f))
        siteRow.addView(Ui.button(this, "加入") { addSite() },
            LinearLayout.LayoutParams(Ui.wrap(), Ui.wrap()).apply { marginStart = dp(8) })
        add(siteRow)

        // 5. 專注模式
        add(Ui.section(this, "5. 專注模式（瀏覽器離題提醒）"))
        add(Ui.note(this, "寫下你現在要做的事。專注期間，你在瀏覽器看的網頁會交給 AI 判斷跟這件事有沒有關係，沒關係就提醒你。"))
        focusGoal = Ui.edit(this, "例如：查資料寫期末報告（主題：台灣能源轉型）", settings.focusGoal,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        add(focusGoal)
        val focusRow = Ui.row(this)
        focusMinutes = Ui.edit(this, "分鐘", "60", InputType.TYPE_CLASS_NUMBER)
        focusRow.addView(focusMinutes, LinearLayout.LayoutParams(dp(80), Ui.wrap()))
        focusRow.addView(Ui.text(this, "分鐘"), LinearLayout.LayoutParams(Ui.wrap(), Ui.wrap()))
        focusBtn = Ui.button(this, "開始專注") { toggleFocus() }
        focusRow.addView(focusBtn, LinearLayout.LayoutParams(0, Ui.wrap(), 1f).apply { marginStart = dp(12) })
        add(focusRow)
        focusStatus = Ui.text(this, "", 14f, Ui.OK)
        add(focusStatus)

        // 6. 規則
        add(Ui.section(this, "6. 給 AI 的規則"))
        add(Ui.note(this, "用自己的話寫，AI 判斷時會優先照這些規則。"))
        rules = Ui.edit(this, "例如：平日晚上 11 點後除非有人找我，不要讓我滑 IG。LINE 回訊息可以，但不要看 LINE TODAY。",
            settings.rules, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { minLines = 3 }
        add(rules)
        val maxRow = Ui.row(this)
        maxRow.addView(Ui.text(this, "單次最多解鎖"))
        maxMinutes = Ui.edit(this, "30", settings.maxMinutes.toString(), InputType.TYPE_CLASS_NUMBER)
        maxRow.addView(maxMinutes, LinearLayout.LayoutParams(dp(72), Ui.wrap()).apply { marginStart = dp(8) })
        maxRow.addView(Ui.text(this, "分鐘"))
        add(maxRow)
        monitorEnabled = CheckBox(this).apply {
            text = "解鎖期間偵測是否離題並提醒"
            setTextColor(Ui.TEXT)
            isChecked = settings.monitorEnabled
        }
        add(monitorEnabled)
        val intRow = Ui.row(this)
        intRow.addView(Ui.text(this, "每隔"))
        monitorInterval = Ui.edit(this, "60", settings.monitorIntervalSec.toString(), InputType.TYPE_CLASS_NUMBER)
        intRow.addView(monitorInterval, LinearLayout.LayoutParams(dp(72), Ui.wrap()).apply { marginStart = dp(8) })
        intRow.addView(Ui.text(this, "秒檢查一次（換頁時也會檢查）"))
        add(intRow)
        add(Ui.note(this, "檢查時會把畫面上的文字（最多約 2500 字，不含密碼欄）送到你選的 AI。只有被鎖的目標解鎖期間、以及專注模式下的瀏覽器會送。"))
        add(Ui.button(this, "儲存設定") { save(quiet = false) })

        // 7. 解鎖中
        add(Ui.section(this, "目前解鎖中"))
        grantList = Ui.column(this)
        add(grantList)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(c)
        }
        setContentView(scroll)
    }

    /** 一個用途的「Claude / Gemini」選擇＋模型欄位；切換時各家的模型分開記 */
    private fun addProviderPicker(parent: LinearLayout, role: Role, title: String) {
        parent.addView(Ui.text(this, title, 14f, Ui.ACCENT).apply { setPadding(0, dp(10), 0, 0) })
        val current = settings.provider(role)
        chosen[role] = current
        val model = Ui.edit(this, current.defaultModel(role), settings.model(role, current))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for (p in Provider.entries) {
            val b = RadioButton(this).apply {
                id = View.generateViewId()
                text = p.displayName
                setTextColor(Ui.TEXT)
                tag = p
            }
            group.addView(b)
            if (p == current) group.check(b.id)
        }
        group.setOnCheckedChangeListener { g, id ->
            val next = g.findViewById<RadioButton>(id)?.tag as? Provider ?: return@setOnCheckedChangeListener
            val prev = chosen[role] ?: return@setOnCheckedChangeListener
            if (prev == next) return@setOnCheckedChangeListener
            settings.setModel(role, prev, model.text.toString())   // 先記住上一家的模型
            chosen[role] = next
            model.hint = next.defaultModel(role)
            model.setText(settings.model(role, next))
        }
        parent.addView(group)
        parent.addView(Ui.note(this, "模型"))
        parent.addView(model)
        modelFields[role] = model
    }

    private fun refresh() {
        val on = GateAccessibilityService.isEnabled(this)
        serviceStatus.text = if (on) "✅ 無障礙服務已開啟" else "❌ 無障礙服務還沒開：到「已安裝的應用程式」裡找「${getString(R.string.service_label)}」並開啟"
        serviceStatus.setTextColor(if (on) Ui.OK else Ui.WARN)

        val apps = settings.lockedApps
        appsSummary.text = if (apps.isEmpty()) "（還沒選）"
        else apps.map { Target.appLabel(this, it) }.sorted().joinToString("、")

        siteList.removeAllViews()
        for (d in settings.lockedSites.sorted()) {
            val row = Ui.row(this)
            row.addView(Ui.text(this, d), LinearLayout.LayoutParams(0, Ui.wrap(), 1f))
            row.addView(Ui.button(this, "移除", primary = false) {
                settings.lockedSites = settings.lockedSites - d
                refresh()
            }, LinearLayout.LayoutParams(Ui.wrap(), Ui.wrap()))
            siteList.addView(row)
        }

        val now = System.currentTimeMillis()
        if (settings.focusActive(now)) {
            val left = (settings.focusUntil - now) / 60_000L + 1
            focusStatus.text = "專注中，還剩約 $left 分鐘：${settings.focusGoal}"
            (focusBtn as TextView).text = "結束專注"
        } else {
            focusStatus.text = ""
            (focusBtn as TextView).text = "開始專注"
        }

        grantList.removeAllViews()
        val grants = Grants.allActive(this)
        if (grants.isEmpty()) grantList.addView(Ui.note(this, "（沒有）"))
        for (g in grants) {
            val left = g.remainingMs(now) / 60_000L + 1
            val row = Ui.row(this)
            val desc = buildString {
                append("${g.label}　還剩約 $left 分鐘")
                if (g.purpose.isNotBlank()) append("\n目的：${g.purpose}")
            }
            row.addView(Ui.text(this, desc, 14f), LinearLayout.LayoutParams(0, Ui.wrap(), 1f))
            row.addView(Ui.button(this, "提前結束", primary = false) {
                Grants.end(this, g.key)
                refresh()
            }, LinearLayout.LayoutParams(Ui.wrap(), Ui.wrap()))
            grantList.addView(row)
        }
    }

    private fun addSite() {
        val d = Browsers.normalizeDomain(newSite.text.toString())
        if (d == null) {
            Toast.makeText(this, "看起來不像網域，例如 youtube.com", Toast.LENGTH_SHORT).show()
            return
        }
        settings.lockedSites = settings.lockedSites + d
        newSite.setText("")
        refresh()
    }

    private fun toggleFocus() {
        if (settings.focusActive()) {
            settings.focusUntil = 0L
        } else {
            val goal = focusGoal.text.toString().trim()
            if (goal.isEmpty()) {
                Toast.makeText(this, "先寫下你要做的事", Toast.LENGTH_SHORT).show()
                return
            }
            val min = focusMinutes.text.toString().toIntOrNull()?.coerceIn(1, 600) ?: 60
            settings.focusGoal = goal
            settings.focusUntil = System.currentTimeMillis() + min * 60_000L
        }
        refresh()
    }

    private fun save(quiet: Boolean) {
        settings.claudeKey = claudeKey.text.toString()
        settings.geminiKey = geminiKey.text.toString()
        for (role in Role.entries) {
            val p = chosen[role] ?: continue
            settings.setProvider(role, p)
            settings.setModel(role, p, modelFields[role]?.text.toString())
        }
        settings.rules = rules.text.toString()
        maxMinutes.text.toString().toIntOrNull()?.let { settings.maxMinutes = it }
        monitorInterval.text.toString().toIntOrNull()?.let { settings.monitorIntervalSec = it }
        settings.monitorEnabled = monitorEnabled.isChecked
        if (!settings.focusActive()) settings.focusGoal = focusGoal.text.toString()
        if (!quiet) {
            Toast.makeText(this, "已儲存", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }
}
