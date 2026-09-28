package tw.claudegate.app

import android.view.accessibility.AccessibilityNodeInfo

/** 從各家瀏覽器的網址列讀出目前網址。 */
object Browsers {
    /** 瀏覽器套件 → 網址列的 view id（各版本可能改名，所以一家可以有多個） */
    private val URL_BAR_IDS: Map<String, List<String>> = mapOf(
        "com.android.chrome" to listOf("url_bar"),
        "com.chrome.beta" to listOf("url_bar"),
        "com.chrome.dev" to listOf("url_bar"),
        "com.chrome.canary" to listOf("url_bar"),
        "com.microsoft.emmx" to listOf("url_bar"),
        "com.brave.browser" to listOf("url_bar"),
        "com.vivaldi.browser" to listOf("url_bar"),
        "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text", "custom_tab_toolbar_url_bar_text"),
        "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
        "org.mozilla.firefox_beta" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
        "com.opera.browser" to listOf("url_field"),
        "com.duckduckgo.mobile.android" to listOf("omnibarTextInput"),
    )

    fun isBrowser(pkg: String?) = pkg != null && pkg in URL_BAR_IDS

    val packages: Set<String> get() = URL_BAR_IDS.keys

    /**
     * 讀網址列文字。使用者正在網址列打字時（有焦點）回傳 null，
     * 否則打到一半的「youtu」就會被當成網址。
     */
    fun readUrl(root: AccessibilityNodeInfo, pkg: String): String? {
        val ids = URL_BAR_IDS[pkg] ?: return null
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id") ?: continue
            for (n in nodes) {
                if (n.isFocused) return null
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) return t
            }
        }
        return null
    }

    /** "https://m.youtube.com/watch?v=1" → "m.youtube.com"；看起來不像網址就回傳 null */
    fun hostOf(urlText: String): String? {
        var s = urlText.trim().lowercase()
        if (s.isEmpty() || s.contains(' ')) return null
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        s = s.removePrefix("www.")
        return if (s.contains('.') && s.all { it.isLetterOrDigit() || it == '.' || it == '-' }) s else null
    }

    /** 使用者在設定頁輸入的網域 → 正規化；"https://www.YouTube.com/xx" → "youtube.com" */
    fun normalizeDomain(input: String): String? = hostOf(input)

    /** host 屬於哪個被鎖的網域（含子網域），沒有就 null */
    fun matchDomain(host: String, domains: Collection<String>): String? =
        domains.firstOrNull { d -> host == d || host.endsWith(".$d") }

    /** 網址列常常只顯示網域，補成可以開的網址 */
    fun toOpenableUrl(urlText: String): String {
        val t = urlText.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }
}
