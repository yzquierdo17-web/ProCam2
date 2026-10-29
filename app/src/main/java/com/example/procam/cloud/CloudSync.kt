package com.example.procam.cloud

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Cloud backup through a folder the user picks with the system file picker.
 *
 * Google Drive, OneDrive, Dropbox and similar apps show up in that picker as storage locations, so
 * copying a file into the picked folder hands it to that app, which then syncs it. No sign-in or
 * API keys are needed in ProCam itself.
 */
object CloudSync {
    const val TAG = "procam-upload"
    private const val PREFS = "procam_cloud"
    private const val KEY_SYNCED = "synced"

    data class Status(val synced: Int, val pending: Int, val failed: Int)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun syncedNames(c: Context): Set<String> =
        prefs(c).getStringSet(KEY_SYNCED, emptySet()) ?: emptySet()

    fun isSynced(c: Context, name: String): Boolean = name in syncedNames(c)

    @Synchronized
    fun markSynced(c: Context, name: String) {
        val set = HashSet(syncedNames(c))   // copy: the set SharedPreferences returns must not be changed
        set.add(name)
        prefs(c).edit().putStringSet(KEY_SYNCED, set).apply()
    }

    /** True while the app still holds write access to the picked folder. */
    fun hasAccess(c: Context, folder: String): Boolean =
        c.contentResolver.persistedUriPermissions.any { it.uri.toString() == folder && it.isWritePermission }

    fun folderName(c: Context, folder: String): String? =
        runCatching { DocumentFile.fromTreeUri(c, Uri.parse(folder))?.name }.getOrNull()

    /** Queues one file for upload (skips files that were already backed up). */
    fun enqueue(c: Context, source: Uri, name: String, mime: String, folder: String, wifiOnly: Boolean) {
        if (isSynced(c, name)) return
        val request = OneTimeWorkRequestBuilder<CloudUploadWorker>()
            .setInputData(workDataOf("src" to source.toString(), "name" to name, "mime" to mime, "folder" to folder))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork("up_$name", ExistingWorkPolicy.KEEP, request)
    }

    /** Queues every ProCam photo and video that hasn't been backed up yet. Returns how many were queued. */
    fun backUpExisting(c: Context, folder: String, wifiOnly: Boolean): Int =
        scan(c, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES, folder, wifiOnly) +
            scan(c, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES, folder, wifiOnly)

    private fun scan(c: Context, collection: Uri, dir: String, folder: String, wifiOnly: Boolean): Int {
        var queued = 0
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        c.contentResolver.query(
            collection, projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$dir/ProCam%"), null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                val name = cur.getString(1) ?: continue
                val mime = cur.getString(2) ?: "application/octet-stream"
                if (isSynced(c, name)) continue
                enqueue(c, ContentUris.withAppendedId(collection, id), name, mime, folder, wifiOnly)
                queued++
            }
        }
        return queued
    }

    fun cancelAll(c: Context) {
        WorkManager.getInstance(c).cancelAllWorkByTag(TAG)
    }

    fun status(c: Context): Flow<Status> =
        WorkManager.getInstance(c).getWorkInfosByTagFlow(TAG).map { list ->
            Status(
                synced = syncedNames(c).size,
                pending = list.count {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED
                },
                failed = list.count { it.state == WorkInfo.State.FAILED },
            )
        }
}
