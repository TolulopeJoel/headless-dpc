package com.tolu.dpc

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/**
 * Home-screen widget. Hiber never freezes an app with a placed widget, so placing this one
 * protects the alarms. Each widget update also re-enforces the focus window.
 */
class FocusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        ScheduleReceiver.enforce(context)
        KeepAliveService.start(context)

        val status = if (ScheduleReceiver.isInRestrictionWindow()) "Locked until 7AM" else "Locks at 12AM"
        widgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_focus).apply {
                setTextViewText(R.id.focus_status, status)
            }
            manager.updateAppWidget(id, views)
        }
    }
}
