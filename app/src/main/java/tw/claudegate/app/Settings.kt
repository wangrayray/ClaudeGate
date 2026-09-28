package tw.claudegate.app

import android.content.Context
import android.content.SharedPreferences

/** 所有使用者設定。存在 App 私有的 SharedPreferences（其他 App 讀不到）。 */
class Settings(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    /** 解鎖對話用的模型 */
    var model: String
        get() = sp.getString("model", DEFAULT_MODEL)?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim()).apply()

    /** 離題偵測用的模型（呼叫頻率高，使用者可以自己換便宜的） */
    var monitorModel: String
        get() = sp.getString("monitor_model", DEFAULT_MODEL)?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("monitor_model", v.trim()).apply()

    /** 被鎖住的 App 套件名稱 */
    var lockedApps: Set<String>
        get() = HashSet(sp.getStringSet("locked_apps", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("locked_apps", HashSet(v)).apply()

    /** 被鎖住的網域（不含 www.，子網域一起鎖） */
    var lockedSites: Set<String>
        get() = HashSet(sp.getStringSet("locked_sites", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("locked_sites", HashSet(v)).apply()

    /** 使用者寫給 Claude 的規則，原文放進 system prompt */
    var rules: String
        get() = sp.getString("rules", "") ?: ""
        set(v) = sp.edit().putString("rules", v.trim()).apply()

    var maxMinutes: Int
        get() = sp.getInt("max_minutes", 30)
        set(v) = sp.edit().putInt("max_minutes", v.coerceIn(1, 240)).apply()

    var monitorEnabled: Boolean
        get() = sp.getBoolean("monitor_enabled", true)
        set(v) = sp.edit().putBoolean("monitor_enabled", v).apply()

    var monitorIntervalSec: Int
        get() = sp.getInt("monitor_interval", 60)
        set(v) = sp.edit().putInt("monitor_interval", v.coerceIn(20, 1800)).apply()

    /** 專注模式：目前要做的事，以及到什麼時候（epoch ms，0 = 沒開） */
    var focusGoal: String
        get() = sp.getString("focus_goal", "") ?: ""
        set(v) = sp.edit().putString("focus_goal", v.trim()).apply()

    var focusUntil: Long
        get() = sp.getLong("focus_until", 0L)
        set(v) = sp.edit().putLong("focus_until", v).apply()

    fun focusActive(now: Long = System.currentTimeMillis()): Boolean =
        focusUntil > now && focusGoal.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
    }
}
