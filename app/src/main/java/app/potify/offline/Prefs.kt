package app.potify.offline

import android.content.Context
import android.content.SharedPreferences

/** User settings stored on the phone. */
object Prefs {
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @JvmStatic
    fun dataSaver(context: Context): Boolean = prefs(context).getBoolean("data_saver", false)

    fun setDataSaver(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("data_saver", enabled).apply()

    fun theme(context: Context): String = prefs(context).getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM

    fun setTheme(context: Context, theme: String) = prefs(context).edit().putString("theme", theme).apply()

    fun askedNotifications(context: Context): Boolean = prefs(context).getBoolean("asked_notifications", false)

    fun setAskedNotifications(context: Context) =
        prefs(context).edit().putBoolean("asked_notifications", true).apply()
}
