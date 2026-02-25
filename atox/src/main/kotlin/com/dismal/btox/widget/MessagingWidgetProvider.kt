// SPDX-FileCopyrightText: 2026 bTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.dismal.btox.MainActivity
import com.dismal.btox.R
import com.dismal.btox.settings.AppLockMode
import com.dismal.btox.settings.Settings

class MessagingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WIDGETS) {
            updateAllWidgets(context)
        }
    }

    private fun buildViews(context: Context): RemoteViews {
        val locked = Settings(context).appLockMode != AppLockMode.None
        val layout = if (locked) R.layout.widget_messaging_locked else R.layout.widget_messaging
        return RemoteViews(context.packageName, layout).apply {
            val openIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            setOnClickPendingIntent(R.id.widget_root, pendingIntent)
        }
    }

    companion object {
        const val ACTION_REFRESH_WIDGETS = "com.dismal.btox.action.REFRESH_WIDGETS"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, MessagingWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                ids.forEach { manager.updateAppWidget(it, MessagingWidgetProvider().buildViews(context)) }
            }
        }
    }
}

