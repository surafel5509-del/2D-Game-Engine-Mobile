package com.sengine.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Tiny content provider used to hand generated files (exported APKs, project zips, game saves) to
 * other apps. Replaces androidx FileProvider so the engine ships without support libraries.
 *
 * URIs look like `content://<appId>.share/<file-name>`; only files inside the app's private
 * "share" directory can be resolved, so the provider cannot leak arbitrary paths.
 */
class ShareProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        shareDir().mkdirs()
        return true
    }

    private fun shareDir(): File = File(requireNotNull(context).filesDir, "share").also { it.mkdirs() }

    /** Copies [source] into the share directory and returns the public URI. */
    fun publish(source: File): Uri? {
        val dir = shareDir()
        val target = File(dir, source.name)
        return try {
            if (source.canonicalPath != target.canonicalPath) source.copyTo(target, overwrite = true)
            Uri.parse("content://${requireNotNull(context).packageName}.share/${target.name}")
        } catch (e: Exception) {
            null
        }
    }

    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        val f = File(shareDir(), name)
        return if (f.exists() && f.canonicalPath.startsWith(shareDir().canonicalPath)) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val row = cursor.newRow()
        for (c in cols) when (c) {
            OpenableColumns.DISPLAY_NAME -> row.add(f.name)
            OpenableColumns.SIZE -> row.add(f.length())
            else -> row.add(null)
        }
        return cursor
    }

    override fun getType(uri: Uri): String = when (uri.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        "png" -> "image/png"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** Publishes a file and returns a shareable URI (null on failure). */
        fun uriFor(ctx: android.content.Context, file: File): Uri? {
            val dir = File(ctx.filesDir, "share").also { it.mkdirs() }
            val target = File(dir, file.name)
            return try {
                if (file.canonicalPath != target.canonicalPath) file.copyTo(target, overwrite = true)
                Uri.parse("content://${ctx.packageName}.share/${target.name}")
            } catch (e: Exception) {
                null
            }
        }
    }
}
