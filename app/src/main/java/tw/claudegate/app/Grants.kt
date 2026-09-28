package tw.claudegate.app

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/** Claude 放行的一次解鎖。key 例如 "app:com.instagram.android"、"site:youtube.com"。 */
data class Grant(
    val key: String,
    val label: String,
    val purpose: String,
    val grantedAt: Long,
    val until: Long,
) {
    fun remainingMs(now: Long = System.currentTimeMillis()) = until - now
}

/** 目前有效的解鎖。 */
object Grants {
    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("grants", Context.MODE_PRIVATE)

    @Synchronized
    fun active(ctx: Context, key: String, now: Long = System.currentTimeMillis()): Grant? {
        val raw = sp(ctx).getString(key, null) ?: return null
        val g = decode(key, raw) ?: return null
        return if (g.until > now) g else null
    }

    /** 最近一筆（包含已過期的），用來判斷「剛剛才過期」 */
    @Synchronized
    fun latest(ctx: Context, key: String): Grant? =
        sp(ctx).getString(key, null)?.let { decode(key, it) }

    @Synchronized
    fun put(ctx: Context, g: Grant) {
        val o = JSONObject()
            .put("label", g.label).put("purpose", g.purpose)
            .put("at", g.grantedAt).put("until", g.until)
        sp(ctx).edit().putString(g.key, o.toString()).apply()
    }

    /** 提前結束：保留紀錄但把到期時間設成現在 */
    @Synchronized
    fun end(ctx: Context, key: String) {
        val g = latest(ctx, key) ?: return
        put(ctx, g.copy(until = minOf(g.until, System.currentTimeMillis())))
    }

    @Synchronized
    fun allActive(ctx: Context): List<Grant> {
        val now = System.currentTimeMillis()
        return sp(ctx).all.mapNotNull { (k, v) -> (v as? String)?.let { decode(k, it) } }
            .filter { it.until > now }
            .sortedBy { it.until }
    }

    private fun decode(key: String, raw: String): Grant? = try {
        val o = JSONObject(raw)
        Grant(key, o.optString("label", key), o.optString("purpose"),
              o.optLong("at"), o.optLong("until"))
    } catch (e: Exception) {
        null
    }
}

/**
 * 每天的解鎖紀錄，讓 Claude 知道「今天已經開了幾次」。
 * 只留今天的資料，換日自動清掉。
 */
object UsageLog {
    data class Day(val approved: Int, val denied: Int, val minutes: Int, val emergency: Int)

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("usage", Context.MODE_PRIVATE)

    private fun today() = LocalDate.now().toString()

    @Synchronized
    private fun load(ctx: Context): JSONObject {
        val sp = sp(ctx)
        if (sp.getString("date", null) != today()) {
            sp.edit().clear().putString("date", today()).apply()
        }
        return try { JSONObject(sp.getString("data", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    }

    @Synchronized
    fun get(ctx: Context, key: String): Day {
        val o = load(ctx).optJSONObject(key) ?: return Day(0, 0, 0, 0)
        return Day(o.optInt("ok"), o.optInt("no"), o.optInt("min"), o.optInt("sos"))
    }

    @Synchronized
    fun totalEmergencyToday(ctx: Context): Int {
        val all = load(ctx)
        return all.keys().asSequence().sumOf { all.optJSONObject(it)?.optInt("sos") ?: 0 }
    }

    @Synchronized
    fun record(ctx: Context, key: String, approved: Boolean, minutes: Int, emergency: Boolean = false) {
        val all = load(ctx)
        val o = all.optJSONObject(key) ?: JSONObject()
        if (emergency) o.put("sos", o.optInt("sos") + 1)
        else if (approved) o.put("ok", o.optInt("ok") + 1) else o.put("no", o.optInt("no") + 1)
        o.put("min", o.optInt("min") + minutes)
        all.put(key, o)
        sp(ctx).edit().putString("data", all.toString()).apply()
    }
}
