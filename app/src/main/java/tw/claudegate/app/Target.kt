package tw.claudegate.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** 被鎖住的東西：一個 App 或一個網站。 */
sealed class Target {
    abstract val key: String
    abstract fun label(ctx: Context): String
    abstract fun icon(ctx: Context): Drawable?
    abstract fun putInto(i: Intent): Intent

    data class App(val pkg: String) : Target() {
        override val key get() = keyOf(pkg)
        override fun label(ctx: Context) = appLabel(ctx, pkg)
        override fun icon(ctx: Context): Drawable? = try {
            ctx.packageManager.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        override fun putInto(i: Intent) = i.putExtra(EXTRA_KIND, "app").putExtra(EXTRA_PKG, pkg)

        companion object {
            fun keyOf(pkg: String) = "app:$pkg"
        }
    }

    /** domain = 被鎖的網域；url = 使用者當時看的網址；browserPkg = 在哪個瀏覽器 */
    data class Site(val domain: String, val url: String, val browserPkg: String?) : Target() {
        override val key get() = keyOf(domain)
        override fun label(ctx: Context) = domain
        override fun icon(ctx: Context): Drawable? = browserPkg?.let { App(it).icon(ctx) }
        override fun putInto(i: Intent) = i.putExtra(EXTRA_KIND, "site")
            .putExtra(EXTRA_DOMAIN, domain).putExtra(EXTRA_URL, url).putExtra(EXTRA_PKG, browserPkg)

        companion object {
            fun keyOf(domain: String) = "site:$domain"
        }
    }

    companion object {
        private const val EXTRA_KIND = "target_kind"
        private const val EXTRA_PKG = "target_pkg"
        private const val EXTRA_DOMAIN = "target_domain"
        private const val EXTRA_URL = "target_url"

        fun from(i: Intent): Target? = when (i.getStringExtra(EXTRA_KIND)) {
            "app" -> i.getStringExtra(EXTRA_PKG)?.let { App(it) }
            "site" -> i.getStringExtra(EXTRA_DOMAIN)?.let { d ->
                Site(d, i.getStringExtra(EXTRA_URL) ?: d, i.getStringExtra(EXTRA_PKG))
            }
            else -> null
        }

        fun appLabel(ctx: Context, pkg: String): String = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}
