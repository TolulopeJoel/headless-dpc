package com.tolu.dpc

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * His own wallpaper, tinted for the part of the day: the dawn of the Bible hour, tan while the entry is due, green in
 * the field, amber for study, blue for work, purple for the banks, dusk, the night; and his own red, untouched, for free
 * time, so red always means free. Each mode has two images in the assets: wallpaper_<mode>_home.jpg (with the mode's
 * name just above the dock) for the home screen and wallpaper_<mode>.jpg for the lock screen. Set only when the mode
 * changes.
 */
object ModeWallpaper {
    private const val TAG = "ModeWallpaper"
    private const val PREFS = "wallpaper"

    /**
     * Free time is what the laptop calls free: nothing on (no field, study, work or banks), not the Bible hour, and
     * before the laptop's night (21:00, Saturday 20:00), so an uncommitted evening is free too.
     */
    fun modeOf(context: Context, state: ScheduleReceiver.Companion.State): String {
        val minute = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
        val night = minute >= ScheduleReceiver.deviceRulesFrom() || minute < 5 * 60
        return when {
            night -> "night"
            minute < 7 * 60 -> "morning"
            state.gated -> "entry"
            state.field -> "field"
            state.study -> "study"
            WorkTime.holding(context) != null -> "work"
            BankTime.holding(context) != null -> "bank"
            else -> "free"
        }
    }

    fun apply(context: Context, state: ScheduleReceiver.Companion.State) {
        val mode = modeOf(context, state)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Keyed by version too, so a new build with new images sets them again.
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)
        val key = "$mode@$version"
        if (prefs.getString("mode", null) == key) return
        val assets = context.assets.list("").orEmpty()
        val home = "wallpaper_${mode}_home.jpg"
        val lock = "wallpaper_$mode.jpg"
        if (home !in assets || lock !in assets) return
        runCatching {
            val wm = WallpaperManager.getInstance(context)
            context.assets.open(home).use { wm.setBitmap(BitmapFactory.decodeStream(it), null, true, WallpaperManager.FLAG_SYSTEM) }
            context.assets.open(lock).use { wm.setBitmap(BitmapFactory.decodeStream(it), null, true, WallpaperManager.FLAG_LOCK) }
            prefs.edit().putString("mode", key).apply()
            Log.d(TAG, "wallpaper: $mode")
        }.onFailure { Log.e(TAG, "setting the wallpaper failed", it) }
    }
}

/** A see-through, full-screen view of the wallpaper alone, for adb to photograph (the wallpaper file itself is sealed). */
class WallpaperPeekActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this))
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
