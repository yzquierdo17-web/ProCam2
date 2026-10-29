package com.example.procam.cloud

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** Copies one saved photo or video into the cloud folder the user picked. */
class CloudUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val src = inputData.getString("src")?.let(Uri::parse) ?: return@withContext Result.failure()
        val name = inputData.getString("name") ?: return@withContext Result.failure()
        val mime = inputData.getString("mime") ?: "application/octet-stream"
        val folder = inputData.getString("folder") ?: return@withContext Result.failure()

        if (CloudSync.isSynced(ctx, name)) return@withContext Result.success()
        // access revoked: retrying can't help, the user has to pick the folder again
        if (!CloudSync.hasAccess(ctx, folder)) return@withContext Result.failure()
        val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(folder))
        if (dir == null || !dir.canWrite()) return@withContext Result.failure()

        var target: DocumentFile? = null
        try {
            dir.findFile(name)?.delete()   // clear a partial copy left by an earlier attempt
            target = dir.createFile(mime, name) ?: return@withContext retryOrFail()
            val input = ctx.contentResolver.openInputStream(src) ?: return@withContext Result.failure()
            val output = ctx.contentResolver.openOutputStream(target.uri, "w") ?: return@withContext retryOrFail()
            input.use { i -> output.use { o -> i.copyTo(o, 256 * 1024) } }
            CloudSync.markSynced(ctx, name)
            Result.success()
        } catch (e: FileNotFoundException) {
            // the photo was deleted before it could be uploaded
            runCatching { target?.delete() }
            Result.failure()
        } catch (e: Exception) {
            runCatching { target?.delete() }
            retryOrFail()
        }
    }

    private fun retryOrFail(): Result = if (runAttemptCount >= 5) Result.failure() else Result.retry()
}
