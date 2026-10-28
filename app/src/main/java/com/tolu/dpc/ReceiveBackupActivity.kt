package com.tolu.dpc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast

/**
 * The share target for Field Service's backups: Backup → "E-mail etc." → "Read my visits". Reads the file it's handed,
 * updates People, and closes. No screen of its own. FieldBackup drives the taps on the way home from field service.
 */
class ReceiveBackupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = streamOf(intent)
        val app = applicationContext
        if (uri == null) {
            Log.w(TAG, "shared without a file: ${intent.action} ${intent.type} ${intent.extras?.keySet()}")
            Toast.makeText(app, "That share had no file in it.", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val name = nameOf(uri)
        val raw = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        finish()
        kotlin.concurrent.thread(name = "fieldservice-shared") {
            val ok = raw != null && runCatching { FieldService.readShared(app, raw, name) }.onFailure { Log.e(TAG, "reading $name failed", it) }.getOrDefault(false)
            Log.d(TAG, "shared $name (${raw?.size ?: 0} bytes): ${if (ok) "read" else "not read"}")
            FieldBackup.received(app, ok)
        }
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent): Uri? =
        (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            ?: (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull())
            ?: intent.data

    private fun nameOf(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "backup"

    companion object { private const val TAG = "ReceiveBackup" }
}
