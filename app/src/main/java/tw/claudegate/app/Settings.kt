package tw.claudegate.app

import android.content.Context
import android.content.SharedPreferences

/** 所有使用者設定。存在 App 私有的 SharedPreferences（其他 App 讀不到）。 */
class Settings(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var claudeKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var geminiKey: String
        get() = sp.getString("gemini_key", "") ?: ""
        set(v) = sp.edit().putString("gemini_key", v.trim()).apply()

    /** 解鎖對話、離題偵測各自用哪家的 AI（可以一個用 Claude、一個用免費的 Gemini） */
    fun provider(role: Role): Provider =
        Provider.fromId(sp.getString("provider_${role.id}", null))

    fun setProvider(role: Role, p: Provider) =
        sp.edit().putString("provider_${role.id}", p.id).apply()

    /** 每家、每個用途各記一個模型，切換供應商時不會互相覆蓋 */
    fun model(role: Role, p: Provider = provider(role)): String =
        sp.getString(modelKey(role, p), null)?.ifBlank { null } ?: p.defaultModel(role)

    fun setModel(role: Role, p: Provider, v: String) =
        sp.edit().putString(modelKey(role, p), v.trim()).apply()

    fun apiKey(p: Provider): String = when (p) {
        Provider.CLAUDE -> claudeKey
        Provider.GEMINI -> geminiKey
    }

    // 舊版的 "model"／"monitor_model" 就是 Claude 的兩個模型，沿用同一個 key
    private fun modelKey(role: Role, p: Provider) = when {
        p == Provider.CLAUDE && role == Role.GATE -> "model"
        p == Provider.CLAUDE && role == Role.MONITOR -> "monitor_model"
        else -> "model_${p.id}_${role.id}"
    }

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

}

/** AI 的兩種用途 */
enum class Role(val id: String) { GATE("gate"), MONITOR("monitor") }

/** 支援的 AI 供應商 */
enum class Provider(val id: String, val displayName: String) {
    CLAUDE("claude", "Claude"),
    GEMINI("gemini", "Gemini");

    fun defaultModel(role: Role): String = when (this) {
        CLAUDE -> "claude-opus-5"
        GEMINI -> if (role == Role.GATE) "gemini-3.8-flash" else "gemini-3.5-flash-lite"
    }

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: CLAUDE
    }
}
