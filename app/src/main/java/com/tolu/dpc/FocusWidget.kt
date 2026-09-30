package com.tolu.dpc

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews

/**
 * Home-screen widget. Hiber never freezes an app with a placed widget, so placing this one
 * protects the alarms. Each widget update also re-enforces the schedule.
 */
class FocusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        ScheduleReceiver.enforce(context)   // also refreshes the text below
        KeepAliveService.start(context)
    }

    companion object {
        /** Show the current phase on every placed widget. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FocusWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_focus).apply {
                setTextViewText(R.id.focus_status, ScheduleReceiver.statusText(context))
            }
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
