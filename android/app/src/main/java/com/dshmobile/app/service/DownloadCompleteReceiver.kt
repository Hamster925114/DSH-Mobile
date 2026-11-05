package com.dshmobile.app.service

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.dshmobile.app.R
import java.io.File

/**
 * Adds open/share actions to a completed DownloadManager download. The system
 * download notification stays visible; this one is the DSH Mobile shortcut.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0) return
        runCatching { notifyComplete(context, id) }
    }

    private fun notifyComplete(context: Context, id: Long) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(id)
        manager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) return
            val name = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                ?: "DSH 文件"
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE))
                ?: MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            val uri = manager.getUriForDownloadedFile(id) ?: localUri(context, cursor)
            if (uri == null) return

            createChannel(context)
            val open = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(share, "分享文件").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val openPending = PendingIntent.getActivity(
                context,
                (id % 100000).toInt() + 40000,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val sharePending = PendingIntent.getActivity(
                context,
                (id % 100000).toInt() + 50000,
                chooser,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_dsh)
                .setContentTitle("下载完成")
                .setContentText(name)
                .setStyle(NotificationCompat.BigTextStyle().bigText(name))
                .setAutoCancel(true)
                .setContentIntent(openPending)
                .addAction(0, "打开", openPending)
                .addAction(0, "分享", sharePending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(3000 + (id % 1000).toInt(), notification)
        }
    }

    private fun localUri(context: Context, cursor: android.database.Cursor): Uri? {
        val local = cursor.getString(
            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI),
        ) ?: return null
        val file = Uri.parse(local).path?.let(::File) ?: return null
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "DSH 下载", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        private const val CHANNEL = "dsh_downloads"
    }
}
