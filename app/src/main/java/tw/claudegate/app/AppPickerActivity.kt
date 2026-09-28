package tw.claudegate.app

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.claudegate.app.Ui.dp

/** 勾選要鎖住的 App（只列有桌面圖示的 App）。 */
class AppPickerActivity : Activity() {

    private class AppRow(val pkg: String, val label: String, val icon: Drawable?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: Settings
    private val selected = HashSet<String>()
    private var all: List<AppRow> = emptyList()
    private var shown: List<AppRow> = emptyList()
    private lateinit var adapter: BaseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        selected += settings.lockedApps

        val root = Ui.column(this, 16).apply { setBackgroundColor(Ui.BG) }
        root.addView(Ui.text(this, "勾選要鎖住的 App", 20f, bold = true))
        val search = Ui.edit(this, "搜尋")
        root.addView(search)
        root.addView(Ui.button(this, "一鍵勾選常見社群／影音 App", primary = false) {
            selected += all.map { it.pkg }.filter { it in COMMON_DISTRACTIONS }
            adapter.notifyDataSetChanged()
        })
        val list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(Ui.match(), 0, 1f))
        root.addView(Ui.button(this, "完成") { finish() })
        setContentView(root)

        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: newRow()
                val app = shown[position]
                (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
                (row.getChildAt(1) as TextView).text = app.label
                (row.getChildAt(2) as CheckBox).isChecked = app.pkg in selected
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val p = shown[position].pkg
            if (!selected.remove(p)) selected += p
            adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })

        scope.launch {
            all = withContext(Dispatchers.IO) { loadApps() }
            filter(search.text.toString())
        }
    }

    override fun onPause() {
        settings.lockedApps = selected
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun newRow(): LinearLayout = Ui.row(this).apply {
        setPadding(0, dp(6), 0, dp(6))
        addView(ImageView(context), LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(Ui.text(context, ""), LinearLayout.LayoutParams(0, Ui.wrap(), 1f).apply { marginStart = dp(12) })
        // 點整列切換，CheckBox 只負責顯示
        addView(CheckBox(context).apply { isClickable = false; isFocusable = false })
    }

    private fun filter(q: String) {
        val query = q.trim().lowercase()
        shown = all.filter { query.isEmpty() || it.label.lowercase().contains(query) || it.pkg.contains(query) }
            .sortedWith(compareBy<AppRow> { it.pkg !in selected }.thenBy { it.label.lowercase() })
        adapter.notifyDataSetChanged()
    }

    private fun loadApps(): List<AppRow> {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName && it !in NEVER_LOCK }
            .map { pkg ->
                val info = pm.getApplicationInfo(pkg, 0)
                AppRow(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
            }
    }

    companion object {
        /** 鎖了可能出事的：設定、撥號（緊急電話）、系統桌面 */
        private val NEVER_LOCK = setOf(
            "com.android.settings", "com.android.dialer", "com.google.android.dialer",
            "com.samsung.android.dialer", "com.android.phone",
        )

        private val COMMON_DISTRACTIONS = setOf(
            "com.instagram.android",            // Instagram
            "com.instagram.barcelona",          // Threads
            "jp.naver.line.android",            // LINE
            "com.facebook.katana",              // Facebook
            "com.facebook.orca",                // Messenger
            "com.zhiliaoapp.musically",         // TikTok
            "com.ss.android.ugc.trill",         // TikTok（亞洲版）
            "com.google.android.youtube",       // YouTube
            "com.twitter.android",              // X
            "com.reddit.frontpage",             // Reddit
            "com.sparkslab.dcardreader",        // Dcard
            "tv.twitch.android.app",            // Twitch
            "com.netflix.mediaclient",          // Netflix
            "com.shopee.tw",                    // 蝦皮
        )
    }
}
