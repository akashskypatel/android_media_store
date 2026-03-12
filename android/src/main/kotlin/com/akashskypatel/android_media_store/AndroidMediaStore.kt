/*
 * MIT License
 * 
 * Copyright (c) 2026 Akash Patel
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.akashskypatel.android_media_store

import android.app.Activity
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.URLConnection

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class AndroidMediaStore(private val activity: Activity) {
    companion object {
        private const val TAG = "AndroidMediaStore"
        private const val CONTENT_URI_PREFIX = "content://"
        private val DIRECTORY_COLLECTION_21 = mapOf(
            Environment.DIRECTORY_MUSIC to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_PODCASTS to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_RINGTONES to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_ALARMS to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_NOTIFICATIONS to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_PICTURES to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_MOVIES to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_DCIM to MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        )

        @RequiresApi(Build.VERSION_CODES.S)
        private val DIRECTORY_COLLECTION_31 = mapOf(
            Environment.DIRECTORY_DOCUMENTS to MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            Environment.DIRECTORY_DOWNLOADS to MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_AUDIOBOOKS to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_SCREENSHOTS to MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_RECORDINGS to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        )
        private val DIRECTORY_COLLECTION
            get(): Map<String, Uri> {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    DIRECTORY_COLLECTION_21 + DIRECTORY_COLLECTION_31
                } else {
                    DIRECTORY_COLLECTION_21
                }
            }

        private val MEDIA_DIRECTORIES: Map<String, String> by lazy {
            val baseDirs = mutableMapOf(
                Environment.DIRECTORY_MUSIC to "Music/",
                Environment.DIRECTORY_PODCASTS to "Podcasts/",
                Environment.DIRECTORY_RINGTONES to "Ringtones/",
                Environment.DIRECTORY_ALARMS to "Alarms/",
                Environment.DIRECTORY_NOTIFICATIONS to "Notifications/",
                Environment.DIRECTORY_PICTURES to "Pictures/",
                Environment.DIRECTORY_MOVIES to "Movies/",
                Environment.DIRECTORY_DOWNLOADS to "Download/",
                Environment.DIRECTORY_DCIM to "DCIM/",
                Environment.DIRECTORY_DOCUMENTS to "Documents/"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                baseDirs[Environment.DIRECTORY_AUDIOBOOKS] = "Audiobooks/"
                baseDirs[Environment.DIRECTORY_SCREENSHOTS] = "Pictures/Screenshots/"
                baseDirs[Environment.DIRECTORY_RECORDINGS] = "Recordings/"
            }
            baseDirs
        }
    }

    // Dynamic Request Codes to handle concurrent requests
    private var nextRequestCode = 10000

    // Operation Maps (RequestCode -> Operation)
    private val pendingWrites = mutableMapOf<Int, WriteOperation>()
    private val pendingDeletes = mutableMapOf<Int, DeleteOperation>()

    data class MediaDetails(
        val displayName: String,
        val mimeType: String,
        val relativePath: String
    )

    data class WriteOperation(
        val requestId: String,
        val destinationUri: Uri,
        val tempFile: File? = null,
        val sourceUri: Uri? = null
    )

    data class DeleteOperation(
        val requestId: String,
        val uri: Uri
    )

    // ─────────────────────────────
    // PUBLIC API METHODS
    // ─────────────────────────────
    fun isInitialized(): Boolean = true

    fun pathToUri(context: Context, path: String, mimeType: String? = null): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        if (path.lowercase().startsWith(CONTENT_URI_PREFIX.lowercase())) {
            context.contentResolver.query(Uri.parse(path), projection, null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) return Uri.parse(path)
                }
        } else {
            val file = File(path)
            if (!file.exists()) return null

            val resolvedMime = mimeType ?: getMimeTypeFromFile(context, file)
            val collection = getCollectionForMimeType(resolvedMime)
            val selection = "${MediaStore.MediaColumns.DATA}=?"
            val selectionArgs = arrayOf(file.absolutePath)

            context.contentResolver.query(collection, projection, selection, selectionArgs, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        return Uri.withAppendedPath(collection, id.toString())
                    }
                }
            val fileProviderAuthority = "${context.packageName}.fileprovider"
            return try {
                FileProvider.getUriForFile(context, fileProviderAuthority, file)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting uri: ${e.message}", e)
                null
            }
        }
        return null
    }

    fun uriToPath(context: Context, uri: Uri): String? {
        return try {
            val fileProviderAuthority = "${context.packageName}.fileprovider"
            if (uri.scheme == "file") {
                return uri.path
            } else if (uri.authority?.lowercase() == fileProviderAuthority.lowercase()) {
                val pathSegments = uri.pathSegments
                return if (pathSegments.isNotEmpty()) {
                    when (pathSegments[0]) {
                        "internal_files" -> File(context.filesDir, pathSegments.drop(1).joinToString("/")).absolutePath
                        "external_files" -> context.getExternalFilesDir(null)?.let { File(it, pathSegments.drop(1).joinToString("/")).absolutePath }
                        "cache" -> File(context.cacheDir, pathSegments.drop(1).joinToString("/")).absolutePath
                        "external_cache" -> context.externalCacheDir?.let { File(it, pathSegments.drop(1).joinToString("/")).absolutePath }
                        else -> File(context.filesDir, pathSegments.joinToString("/")).absolutePath
                    }
                } else null
            } else if (uri.authority?.lowercase() == "media") {
                val projection = arrayOf(MediaStore.MediaColumns.DATA)
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        return cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
                    }
                }
            } else if (uri.authority == "com.android.externalStorage.documents") {
                return parseDocumentUri(uri)
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error converting URI to path: ${e.message}", e)
            null
        }
    }

    fun editMediaFile(
        context: Context,
        requestId: String,
        pathOrUri: String,
        data: ByteArray,
    ): Any? {
        return runCatching {
            val uri = resolveUriFromString(context, pathOrUri) ?: return null
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                clearPending(context, uri)
                uri
            } catch (e: SecurityException) {
                handleSecurityExceptionForWrite(context, e, requestId, uri, data = data)
                "PENDING_AUTH"
            }
        }.getOrElse { e ->
            Log.e(TAG, "Failed to edit media file at $pathOrUri: ${e.message}", e)
            null
        }
    }

    fun getReadableMediaFilePath(context: Context, pathOrUri: String): String? {
        return runCatching {
            val uri = resolveUriFromString(context, pathOrUri) ?: return pathOrUri

            if (uri.scheme == "file") {
                return uri.path ?: pathOrUri
            }

            val tempFile = File(context.cacheDir, "media_cache_${System.currentTimeMillis()}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Failed to open input stream for $uri")

            tempFile.absolutePath

        }.onFailure { e ->
            Log.e(TAG, "Error resolving readable file path for $pathOrUri: ${e.message}", e as Exception)
        }.getOrNull()
    }

    fun readMediaFile(context: Context, pathOrUri: String, maxLimitBytes: Int = 1024 * 1024): ByteArray {
        val uri = resolveUriFromString(context, pathOrUri)
        val inputStream = uri?.let { context.contentResolver.openInputStream(it) }
            ?: File(pathOrUri).inputStream()

        inputStream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var totalRead = 0
            var bytesRead: Int

            while (input.read(chunk).also { bytesRead = it } != -1) {
                totalRead += bytesRead
                if (totalRead > maxLimitBytes) {
                    throw IOException("FILE_TOO_LARGE: File exceeds the ${maxLimitBytes / 1024 / 1024}MB limit for direct byte reading.")
                }
                buffer.write(chunk, 0, bytesRead)
            }
            return buffer.toByteArray()
        }
    }

    fun deleteMediaFile(context: Context, requestId: String, identifier: String): Any? {
        val uri = resolveUriFromString(context, identifier) ?: return false
        return try {
            val deleted = context.contentResolver.delete(uri, null, null) > 0
            if (!deleted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                createDeleteRequest(context, requestId, uri)
                return "PENDING_AUTH"
            }
            true // Handled or queued
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                createDeleteRequest(context, requestId, uri)
                return "PENDING_AUTH"
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                val rse = e as? RecoverableSecurityException
                if (rse != null) {
                    val requestCode = nextRequestCode++
                    pendingDeletes[requestCode] = DeleteOperation(requestId, uri)
                    activity.startIntentSenderForResult(
                        rse.userAction.actionIntent.intentSender,
                        requestCode, null, 0, 0, 0, null
                    )
                    return "PENDING_AUTH"
                } else false
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete: ${e.message}", e)
            false
        }
    }

    fun createMediaFileAtRelative(
        context: Context,
        requestId: String,
        displayName: String,
        relativePath: String? = null,
        data: ByteArray,
        mimeType: String? = null
    ): Any? {
        return runCatching {
            val mime = mimeType ?: getMimeTypeFromBytes(data)
            val relative = relativePath?.let { validateRelativePath(it) }
            
            if (relative != null && mime != null) {
                val collection = getCollectionForMimeType(mime)
                var adjustedRelative = relative
                if (collection == MediaStore.Downloads.EXTERNAL_CONTENT_URI &&
                    !adjustedRelative.startsWith(Environment.DIRECTORY_DOWNLOADS)
                ) {
                    adjustedRelative = "${Environment.DIRECTORY_DOWNLOADS}/$adjustedRelative"
                }

                val uri = findOrCreateMediaStoreEntry(context, null, MediaDetails(displayName, mime, adjustedRelative))
                if (uri != null) {
                    try {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                        clearPending(context, uri)
                        uri
                    } catch (e: SecurityException) {
                        handleSecurityExceptionForWrite(context, e, requestId, uri, data = data)
                        "PENDING_AUTH"
                    }
                } else {
                    Log.e(TAG, "Failed to create media entry at $adjustedRelative: $displayName, $mimeType")
                    null
                }
            } else {
                Log.e(TAG, "Failed to create media file: Invalid relativePath or mimeType")
                null
            }
        }.getOrElse { e ->
            Log.e(TAG, "Error creating media file: ${e.message}", e)
            null
        }
    }

    fun createMediaFile(
        context: Context,
        requestId: String,
        displayName: String,
        data: ByteArray,
        mimeType: String? = null
    ): Any? {
        return runCatching {
            val mime = mimeType ?: getMimeTypeFromBytes(data)
            var relativePath = mime?.let { getRelativeForMimeType(mime) }

            if (mime != null && relativePath != null) {
                val collection = getCollectionForMimeType(mime)
                if (collection == MediaStore.Downloads.EXTERNAL_CONTENT_URI &&
                    !relativePath.startsWith(Environment.DIRECTORY_DOWNLOADS)
                ) {
                    relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$relativePath"
                }

                val uri = findOrCreateMediaStoreEntry(context, null, MediaDetails(displayName, mime, relativePath))
                if (uri != null) {
                    try {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                        clearPending(context, uri)
                        uri
                    } catch (e: SecurityException) {
                        handleSecurityExceptionForWrite(context, e, requestId, uri, data = data)
                        "PENDING_AUTH"
                    }
                } else null
            } else null
        }.getOrElse { e ->
            Log.e(TAG, "Error creating media file: ${e.message}", e)
            null
        }
    }

    fun copyMediaFileToRelative(
        context: Context,
        requestId: String,
        displayName: String,
        sourcePathOrUri: String,
        relativePath: String? = null,
        mimeType: String? = null
    ): Any? {
        return runCatching {
            val sourceUri = resolveUriFromString(context, sourcePathOrUri)
            val mime = mimeType ?: sourceUri?.let { context.contentResolver.getType(it) } ?: getMimeTypeFromFile(context, File(sourcePathOrUri))
            var relative = relativePath?.let { validateRelativePath(it) } ?: mime?.let { getRelativeForMimeType(it) }

            if (relative != null && mime != null) {
                val collection = getCollectionForMimeType(mime)
                if (collection == MediaStore.Downloads.EXTERNAL_CONTENT_URI && !relative.startsWith(Environment.DIRECTORY_DOWNLOADS)) {
                    relative = "${Environment.DIRECTORY_DOWNLOADS}/$relative"
                }
            }

            if (relative == null || sourceUri == null || mime == null) return@runCatching null

            val destinationUri = findOrCreateMediaStoreEntry(context, null, MediaDetails(displayName, mime, relative)) ?: return@runCatching null

            try {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    context.contentResolver.openOutputStream(destinationUri, "wt")?.use { output ->
                        input.copyTo(output)
                    } ?: throw IOException("Failed to open output stream")
                } ?: throw IOException("Failed to open input stream")
                clearPending(context, destinationUri)
                return@runCatching destinationUri
            } catch (e: SecurityException) {
                handleSecurityExceptionForWrite(context, e, requestId, destinationUri, sourceUri = sourceUri)
                "PENDING_AUTH"
            }
        }.getOrElse { e ->
            Log.e(TAG, "Error copying media file: ${e.message}", e)
            null
        }
    }

    fun copyMediaFileToPathOrUri(
        context: Context,
        requestId: String,
        toPathOrUri: String,
        fromPathOrUri: String,
        mimeType: String? = null
    ): Any? {
        return runCatching {
            val toUri = resolveUriFromString(context, toPathOrUri)
            val fromUri = resolveUriFromString(context, fromPathOrUri)
            val collection = getStorageCollection(context, toUri.toString())
            if (toUri == null || fromUri == null || collection == null) return null

            val destinationUri = findOrCreateMediaStoreEntry(context, toUri.toString()) ?: return@runCatching null

            try {
                context.contentResolver.openInputStream(fromUri)?.use { input ->
                    context.contentResolver.openOutputStream(destinationUri, "wt")?.use { output ->
                        input.copyTo(output)
                    } ?: throw IOException("Failed to open output stream")
                } ?: throw IOException("Failed to open input stream")
                clearPending(context, destinationUri)
                return@runCatching destinationUri
            } catch (e: SecurityException) {
                handleSecurityExceptionForWrite(context, e, requestId, destinationUri, sourceUri = fromUri)
                "PENDING_AUTH"
            }
        }.getOrElse { e ->
            Log.e(TAG, "Error copying media file: ${e.message}", e)
            null
        }
    }

    // ────────────────────────────────────────────────
    // CONCURRENCY & RESULT HELPERS
    // ────────────────────────────────────────────────

    fun isPendingWrite(requestCode: Int): Boolean = pendingWrites.containsKey(requestCode)
    fun isPendingDelete(requestCode: Int): Boolean = pendingDeletes.containsKey(requestCode)

    fun executePendingWriteOperation(requestCode: Int): Pair<String, Uri?>? {
        val operation = pendingWrites.remove(requestCode) ?: return null
        var finalUri: Uri? = operation.destinationUri
        
        runCatching {
            when {
                operation.tempFile != null -> {
                    activity.contentResolver.openOutputStream(operation.destinationUri, "wt")?.use { out ->
                        operation.tempFile.inputStream().use { it.copyTo(out) }
                    }
                    operation.tempFile.delete() // Clean up temp file
                    clearPending(activity, operation.destinationUri)
                }
                operation.sourceUri != null -> {
                    activity.contentResolver.openInputStream(operation.sourceUri)?.use { input ->
                        activity.contentResolver.openOutputStream(operation.destinationUri, "wt")?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    clearPending(activity, operation.destinationUri)
                }
                else -> throw IOException("No data source provided")
            }
        }.onFailure { e ->
            Log.e(TAG, "Error executing write operation: ${e.message}", e)
            finalUri = null // Instructs plugin to throw an error back to Dart
        }
        return Pair(operation.requestId, finalUri)
    }

    fun cancelPendingWriteOperation(requestCode: Int): String? {
        val operation = pendingWrites.remove(requestCode)
        operation?.tempFile?.delete() // Cleanup unused temp file
        return operation?.requestId
    }

    fun executePendingDeleteOperation(requestCode: Int): Pair<String, Boolean>? {
        val operation = pendingDeletes.remove(requestCode) ?: return null
        val success = runCatching {
            var deleted = activity.contentResolver.delete(operation.uri, null, null) > 0
            if (!deleted) {
                deleted = File(operation.uri.toString()).deleteRecursively()
            }
            deleted
        }.getOrDefault(false)
        return Pair(operation.requestId, success)
    }

    fun cancelPendingDeleteOperation(requestCode: Int): String? {
        return pendingDeletes.remove(requestCode)?.requestId
    }

    // ────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ────────────────────────────────────────────────
    
    private fun clearPending(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear IS_PENDING flag for $uri", e)
            }
        }
    }

    private fun getStorageCollection(context: Context, pathOrUri: String?): String? {
        if (pathOrUri == null) return null
        val uri = resolveUriFromString(context, pathOrUri) ?: return null
        val fileProviderAuthority = "${context.packageName}.fileprovider"

        return when (uri.authority?.lowercase()) {
            "media" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val mimeType = context.contentResolver.getType(uri)
                getCollectionForMimeType(mimeType).toString()
            } else {
                val file = File(pathOrUri)
                val mime = getMimeTypeFromFile(context, file)
                mime?.let { getCollectionForMimeType(it).toString() }
            }
            fileProviderAuthority.lowercase() -> {
                val file = File(pathOrUri)
                val mime = getMimeTypeFromFile(context, file)
                mime?.let { getCollectionForMimeType(it).toString() }
            }
            "com.android.externalstorage.documents" -> {
                val path = parseDocumentUri(uri)
                path?.let {
                    val mime = getMimeTypeFromFile(context, File(it))
                    mime?.let { getCollectionForMimeType(it).toString() }
                }
            }
            else -> null
        }
    }

    private fun resolveUriFromString(context: Context, pathOrUri: String): Uri? {
        return if (pathOrUri.startsWith(CONTENT_URI_PREFIX)) {
            Uri.parse(pathOrUri)
        } else {
            pathToUri(context, pathOrUri)
        }
    }

    private fun parseDocumentUri(uri: Uri): String? {
        val docId = DocumentsContract.getDocumentId(uri)
        val split = docId.split(":")

        return if (split.size >= 2) {
            val storageType = split[0]
            val relativePath = split[1]

            when (storageType) {
                "primary" -> Environment.getExternalStorageDirectory().absolutePath + "/" + relativePath
                "home" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath + "/" + relativePath
                else -> "/storage/$storageType/$relativePath"
            }
        } else null
    }

    private fun validateRelativePath(relativePath: String): String? {
        val key = relativePath.trim('/')
        return MEDIA_DIRECTORIES[key]
    }

    private fun doesEntryExist(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { cursor ->
                cursor.moveToFirst()
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error querying for existing MediaStore entry at $uri", e)
            false
        }
    }

    private fun getDetailsFromPath(context: Context, path: String): MediaDetails? {
        val file = File(path)
        if (!file.exists()) return null

        val displayName = getDisplayName(context, path)
        val mimeType = getMimeTypeFromFile(context, file)
        val relativePath = getRelativePath(context, path)

        if (displayName == null || mimeType == null || relativePath == null) return null
        
        return MediaDetails(displayName, mimeType, relativePath)
    }

    private fun createNewMediaStoreEntry(context: Context, details: MediaDetails): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, details.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, details.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, details.relativePath)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val collection = getCollectionForMimeType(details.mimeType)
            context.contentResolver.insert(collection, values)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating new MediaStore entry", e)
            null
        }
    }

    fun findOrCreateMediaStoreEntry(
        context: Context,
        pathOrUri: String? = null,
        mediaDetails: MediaDetails? = null
    ): Uri? {
        if (pathOrUri == null && mediaDetails == null) {
            Log.e(TAG, "Both pathOrUri and mediaDetails cannot be null.")
            return null
        }
        pathOrUri?.let { pathString ->
            resolveUriFromString(context, pathString)?.let { uri ->
                if (doesEntryExist(context, uri)) return uri
            }
        }
        val detailsToCreate = mediaDetails ?: pathOrUri?.let { getDetailsFromPath(context, it) }

        if (detailsToCreate == null) {
            Log.e(TAG, "Could not determine media details needed for creation.")
            return null
        }

        return createNewMediaStoreEntry(context, detailsToCreate)
    }

    private fun handleSecurityExceptionForWrite(
        context: Context,
        e: SecurityException,
        requestId: String,
        destinationUri: Uri,
        data: ByteArray? = null,
        sourceUri: Uri? = null
    ) {
        val requestCode = nextRequestCode++
        val tempFile = data?.let { bytes ->
            val file = File(context.cacheDir, "pending_write_${System.currentTimeMillis()}")
            file.writeBytes(bytes)
            file
        }
        
        pendingWrites[requestCode] = WriteOperation(requestId, destinationUri, tempFile, sourceUri)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, listOf(destinationUri))
                activity.startIntentSenderForResult(pendingIntent.intentSender, requestCode, null, 0, 0, 0, null)
            } catch (ex: Exception) {
                pendingWrites.remove(requestCode)
                tempFile?.delete()
                throw ex
            }
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            val rse = e as? RecoverableSecurityException
            if (rse != null) {
                try {
                    activity.startIntentSenderForResult(rse.userAction.actionIntent.intentSender, requestCode, null, 0, 0, 0, null)
                } catch (ex: Exception) {
                    pendingWrites.remove(requestCode)
                    tempFile?.delete()
                    throw ex
                }
            } else {
                pendingWrites.remove(requestCode)
                tempFile?.delete()
                throw e
            }
        } else {
            pendingWrites.remove(requestCode)
            tempFile?.delete()
            throw e
        }
    }

    private fun createDeleteRequest(context: Context, requestId: String, uri: Uri) {
        val requestCode = nextRequestCode++
        pendingDeletes[requestCode] = DeleteOperation(requestId, uri)
        val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
        activity.startIntentSenderForResult(
            pendingIntent.intentSender,
            requestCode,
            null, 0, 0, 0, null
        )
    }

    private fun getMimeTypeFromFile(context: Context, file: File): String? {
        val uri = Uri.fromFile(file)
        val type = context.contentResolver.getType(uri)
        if (!type.isNullOrEmpty()) return type
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun getMimeTypeFromBytes(data: ByteArray): String? {
        return try {
            val input = data.inputStream()
            input.mark(0)
            val mime = URLConnection.guessContentTypeFromStream(input)
            input.reset()
            mime
        } catch (e: Exception) {
            null
        }
    }

    private fun getCollectionForMimeType(mimeType: String?): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return MediaStore.Files.getContentUri("external")
        }

        return when {
            mimeType == null -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("text/") -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            mimeType == "application/pdf" -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            mimeType == "application/zip" -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            mimeType.startsWith("application/") -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
    }

    private fun getRelativeForMimeType(mimeType: String): String {
        return when {
            mimeType.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            mimeType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
            mimeType.startsWith("text/") -> Environment.DIRECTORY_DOCUMENTS
            mimeType == "application/pdf" -> Environment.DIRECTORY_DOCUMENTS
            mimeType == "application/zip" -> Environment.DIRECTORY_DOWNLOADS
            mimeType.startsWith("application/") -> Environment.DIRECTORY_DOWNLOADS
            else -> Environment.DIRECTORY_DOWNLOADS
        }
    }

    private fun getRelativePath(context: Context, pathOrUri: String): String? {
        val uri = resolveUriFromString(context, pathOrUri)
        return uri?.let {
            when {
                uri.authority == "media" -> {
                    activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                            if (index != -1) cursor.getString(index) else null
                        } else null
                    }
                }
                else -> {
                    val mime = getMimeTypeFromFile(context, File(pathOrUri))
                    mime?.let { mimeType -> getRelativeForMimeType(mimeType) }
                }
            }
        }
    }

    private fun getDisplayName(context: Context, pathOrUri: String): String? {
        val fullName: String? = if (pathOrUri.startsWith(CONTENT_URI_PREFIX)) {
            try {
                context.contentResolver.query(
                    Uri.parse(pathOrUri),
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not get display name from URI, falling back to path parsing.", e)
                Uri.parse(pathOrUri).path?.let { File(it).name }
            }
        } else {
            File(pathOrUri).name
        }

        return fullName?.let { File(it).nameWithoutExtension }
    }
}
