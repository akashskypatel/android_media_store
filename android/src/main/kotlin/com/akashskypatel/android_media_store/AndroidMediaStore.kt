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
import io.flutter.Log
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

        // Media directory mappings
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

    public val DELETE_REQUEST_CODE = 1001
    public val WRITE_REQUEST_CODE = 1002
    public val DELETE_REQUEST_NOTIFY = "notifyDeleteComplete"
    public val WRITE_REQUEST_NOTIFY = "notifyCreateComplete"

    // Permission operation queue
    private val pendingWriteOperations = mutableListOf<WriteOperation>()
    private val pendingDeleteOperations = mutableListOf<DeleteOperation>()

    data class MediaDetails(
        val displayName: String,
        val mimeType: String,
        val relativePath: String
    )

    // Now writes to temp cache file when pending user permission.
    data class WriteOperation(
        val destinationUri: Uri,
        val tempFile: File? = null,
        val sourceUri: Uri? = null,
        val onSuccess: ((Uri?) -> Unit)? = null,
        val onFail: ((Exception) -> Unit)? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as WriteOperation

            if (destinationUri != other.destinationUri) return false
            if (tempFile != other.tempFile) return false
            if (sourceUri != other.sourceUri) return false
            
            return true
        }

        override fun hashCode(): Int {
            var result = destinationUri.hashCode()
            result = 31 * result + (tempFile?.hashCode() ?: 0)
            result = 31 * result + (sourceUri?.hashCode() ?: 0)
            return result
        }
    }

    data class DeleteOperation(
        val uri: Uri,
        val onSuccess: (() -> Unit)? = null,
        val onFail: ((Exception) -> Unit)? = null
    )

    // ─────────────────────────────
    // PUBLIC API METHODS
    // ─────────────────────────────
    fun isInitialized(): Boolean {
        return true
    }

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
                        val id =
                            cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
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
                handleSecurityExceptionForWrite(context, e, uri, data = data)
                "PENDING_AUTH"
            }
        }.getOrElse { e ->
            Log.e(TAG, "Failed to edit media file at $pathOrUri: ${e.message}", e)
            null
        }
    }

    /**
     * Safely resolves a media file into a readable physical file path.
     * If it's a content:// URI, it streams the content into a temporary cache file
     * to prevent OOM errors, and returns the path to that cache file.
     */
    fun getReadableMediaFilePath(
        context: Context,
        pathOrUri: String,
    ): String? {
        return runCatching {
            val uri = resolveUriFromString(context, pathOrUri) ?: return pathOrUri

            // If it's already a standard physical file, just return the path
            if (uri.scheme == "file") {
                return uri.path ?: pathOrUri
            }

            // Create a temporary file in the app's cache directory
            // Note: You can optionally extract the file extension from the mimeType here
            val tempFile = File(context.cacheDir, "media_cache_${System.currentTimeMillis()}")

            // Stream from the URI to the temp file using an 8KB buffer (prevents OOM)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Failed to open input stream for $uri")

            // Return the absolute path of the safely cached file
            tempFile.absolutePath

        }.onFailure { e ->
            Log.e(TAG, "Error resolving readable file path for $pathOrUri: ${e.message}", e as Exception)
        }.getOrNull()
    }

    /**
     * Reads a small media file directly into memory as a ByteArray.
     * SAFEGUARD: Aborts if the file is larger than 1MB to prevent OOM 
     * and Binder TransactionTooLargeException crashes.
     */
    fun readMediaFile(
        context: Context,
        pathOrUri: String,
        maxLimitBytes: Int = 1024 * 1024 // 1 MB limit for safe IPC transfer
    ): ByteArray {
        val uri = resolveUriFromString(context, pathOrUri)
        val inputStream = uri?.let { context.contentResolver.openInputStream(it) }
            ?: File(pathOrUri).inputStream()

        inputStream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192) // 8KB chunks
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

    fun deleteMediaFile(context: Context, identifier: String): Any? {
        val uri = resolveUriFromString(context, identifier) ?: return false
        return try {
            val deleted = context.contentResolver.delete(uri, null, null) > 0
            if (!deleted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                createDeleteRequest(context, uri)
            }
            true // Handled or queued
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                createDeleteRequest(context, uri)
                return "PENDING_AUTH"
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                val rse = e as? RecoverableSecurityException
                if (rse != null) {
                    pendingDeleteOperations.add(DeleteOperation(uri))
                    activity.startIntentSenderForResult(
                        rse.userAction.actionIntent.intentSender,
                        DELETE_REQUEST_CODE, null, 0, 0, 0, null
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
        displayName: String,
        relativePath: String? = null,
        data: ByteArray,
        mimeType: String? = null
    ): Any? {
        return runCatching {
            val mime = mimeType ?: getMimeTypeFromBytes(data)
            val relative = relativePath?.let { validateRelativePath(it) }
            
            if (relative != null && mime != null) {
                val uri = findOrCreateMediaStoreEntry(context, null, MediaDetails(displayName, mime, relative))
                if (uri != null) {
                    try {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                        clearPending(context, uri)
                        uri
                    } catch (e: SecurityException) {
                        handleSecurityExceptionForWrite(context, e, uri, data = data)
                        "PENDING_AUTH"
                    }
                } else {
                    Log.e(TAG, "Failed to create media entry at $relative: $displayName, $mimeType")
                    null
                }
            } else {
                Log.e(TAG, "Failed to create media file: Invalid relativePath or mimeType: $relativePath, $mimeType")
                null
            }
        }.getOrElse { e ->
            Log.e(TAG, "Error creating media file: ${e.message}", e)
            null
        }
    }

    fun createMediaFile(
        context: Context,
        displayName: String,
        data: ByteArray,
        mimeType: String? = null
    ): Any? {
        return runCatching {
            val mime = mimeType ?: getMimeTypeFromBytes(data)
            val relativePath = mime?.let { getRelativeForMimeType(mime) }
            
            if (mime != null && relativePath != null) {
                val uri = findOrCreateMediaStoreEntry(context, null, MediaDetails(displayName, mime, relativePath))
                if (uri != null) {
                    try {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                        clearPending(context, uri)
                        uri
                    } catch (e: SecurityException) {
                        handleSecurityExceptionForWrite(context, e, uri, data = data)
                        "PENDING_AUTH"
                    }
                } else {
                    Log.e(TAG, "Failed to create media entry at $relativePath: $displayName, $mimeType")
                    null
                }
            } else {
                Log.e(TAG, "Failed to create media file: Invalid relativePath and mimeType")
                null
            }
        }.getOrElse { e ->
            Log.e(TAG, "Error creating media file: ${e.message}", e)
            null
        }
    }

    fun copyMediaFileToRelative(
        context: Context,
        displayName: String,
        sourcePathOrUri: String,
        relativePath: String? = null,
        mimeType: String? = null
    ): Any? {
        return runCatching {
            val sourceUri = resolveUriFromString(context, sourcePathOrUri)
            val mime = mimeType ?: sourceUri?.let { context.contentResolver.getType(it) }
                ?: getMimeTypeFromFile(context, File(sourcePathOrUri))
            val relative = relativePath?.let { validateRelativePath(it) } ?: mime?.let { getRelativeForMimeType(it) }

            if (relative == null || sourceUri == null || mime == null) return@runCatching null

            val destinationUri = findOrCreateMediaStoreEntry(context, null, MediaDetails(displayName, mime, relative))
            if (destinationUri == null) return@runCatching null

            try {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    context.contentResolver.openOutputStream(destinationUri, "wt")?.use { output ->
                        input.copyTo(output)
                    } ?: throw IOException("Failed to open output stream")
                } ?: throw IOException("Failed to open input stream")
                clearPending(context, destinationUri)
                return@runCatching destinationUri
            } catch (e: SecurityException) {
                handleSecurityExceptionForWrite(context, e, destinationUri, sourceUri = sourceUri)
                "PENDING_AUTH"
            }
        }.getOrElse { e ->
            Log.e(TAG, "Error creating media file: ${e.message}", e)
            null
        }
    }

    fun copyMediaFileToPathOrUri(
        context: Context,
        toPathOrUri: String,
        fromPathOrUri: String,
        mimeType: String? = null
    ): Any? {
        return runCatching {
            val toUri = resolveUriFromString(context, toPathOrUri)
            val fromUri = resolveUriFromString(context, fromPathOrUri)
            val collection = getStorageCollection(context, toUri.toString())
            if (toUri == null || fromUri == null || collection == null) return null

            val destinationUri = findOrCreateMediaStoreEntry(context, toUri.toString())
            if (destinationUri == null) return@runCatching null

            try {
                context.contentResolver.openInputStream(fromUri)?.use { input ->
                    context.contentResolver.openOutputStream(destinationUri, "wt")?.use { output ->
                        input.copyTo(output)
                    } ?: throw IOException("Failed to open output stream")
                } ?: throw IOException("Failed to open input stream")
                clearPending(context, destinationUri)
                return@runCatching destinationUri
            } catch (e: SecurityException) {
                handleSecurityExceptionForWrite(context, e, destinationUri, sourceUri = fromUri)
                "PENDING_AUTH"
            }
        }.getOrElse { e ->
            Log.e(TAG, "Error copying media file: ${e.message}", e)
            null
        }
    }

    fun executePendingWriteOperations(): List<Uri>? {
        val operations = pendingWriteOperations.toList()
        pendingWriteOperations.clear()
        
        operations.forEach { operation ->
            runCatching {
                when {
                    operation.tempFile != null -> {
                        activity.contentResolver.openOutputStream(operation.destinationUri, "wt")?.use { out ->
                            operation.tempFile.inputStream().use { it.copyTo(out) }
                        }
                        operation.tempFile.delete() // Clean up temp file
                        clearPending(activity, operation.destinationUri)
                        operation.onSuccess?.invoke(operation.destinationUri)
                    }
                    operation.sourceUri != null -> {
                        activity.contentResolver.openInputStream(operation.sourceUri)?.use { input ->
                            activity.contentResolver.openOutputStream(operation.destinationUri, "wt")?.use { output ->
                                input.copyTo(output)
                            }
                        }
                        clearPending(activity, operation.destinationUri)
                        operation.onSuccess?.invoke(operation.destinationUri)
                    }
                    else -> throw IOException("No data source provided")
                }
            }.onFailure { e ->
                Log.e(TAG, "Error executing write operation: ${e.message}", e)
                operation.onFail?.invoke(e as Exception)
            }
        }
        return operations.map { it.destinationUri }.takeIf { it.isNotEmpty() }
    }

    fun executePendingDeleteOperations(): Boolean {
        val operations = pendingDeleteOperations.toList()
        pendingDeleteOperations.clear()
        Log.d(TAG, "Execute Pending Delete Operations: ${operations.size}")
        return operations.all { operation ->
            runCatching {
                var deleted = activity.contentResolver.delete(operation.uri, null, null) > 0
                if (deleted) {
                    operation.onSuccess?.invoke()
                } else {
                    try {
                        deleted = File(operation.uri.toString()).deleteRecursively()
                    } catch (e: Exception) {
                        operation.onFail?.invoke(IOException("Failed to delete file"))
                        Log.e(TAG, "Error executing delete operation: ${e.message}", e)
                        deleted = false
                    }
                }
                deleted
            }.getOrElse { e ->
                Log.e(TAG, "Error executing delete operation: ${e.message}", e)
                operation.onFail?.invoke(e as Exception)
                false
            }
        }
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
        val relative = "${relativePath.trim('/')}/"
        return MEDIA_DIRECTORIES[relative]
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
        destinationUri: Uri,
        data: ByteArray? = null,
        sourceUri: Uri? = null,
        onSuccess: ((Uri?) -> Unit)? = null,
        onFail: ((Exception) -> Unit)? = null
    ) {
        val tempFile = data?.let { bytes ->
            val file = File(context.cacheDir, "pending_write_${System.currentTimeMillis()}")
            file.writeBytes(bytes)
            file
        }
        
        pendingWriteOperations.add(WriteOperation(destinationUri, tempFile, sourceUri, onSuccess, onFail))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, listOf(destinationUri))
                activity.startIntentSenderForResult(pendingIntent.intentSender, WRITE_REQUEST_CODE, null, 0, 0, 0, null)
            } catch (ex: Exception) {
                onFail?.invoke(ex)
            }
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            val rse = e as? RecoverableSecurityException
            if (rse != null) {
                try {
                    activity.startIntentSenderForResult(rse.userAction.actionIntent.intentSender, WRITE_REQUEST_CODE, null, 0, 0, 0, null)
                } catch (ex: Exception) {
                    onFail?.invoke(ex)
                }
            } else {
                onFail?.invoke(e)
            }
        } else {
            onFail?.invoke(e)
        }
    }

    private fun createDeleteRequest(
        context: Context,
        uri: Uri,
        onSuccess: (() -> Unit)? = null,
        onFail: ((Exception) -> Unit)? = null
    ) {
        runCatching {
            pendingDeleteOperations.add(DeleteOperation(uri, onSuccess, onFail))
            
            // Validate file existence
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use {
                if (!it.moveToFirst()) throw Exception("Could not find requested file $uri")
            } ?: throw Exception("Could not find requested file $uri")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                activity.startIntentSenderForResult(
                    pendingIntent.intentSender,
                    DELETE_REQUEST_CODE,
                    null, 0, 0, 0, null
                )
            }
        }.onSuccess {
            onSuccess?.invoke()
        }.onFailure { e ->
            when (e) {
                is PendingIntent.CanceledException -> Log.e(TAG, "Delete permission request canceled for URI: $uri", e)
                is SecurityException -> Log.e(TAG, "Error requesting delete permission: ${e.message}", e)
                else -> Log.e(TAG, "Error occurred when deleting file: ${e.message}", e)
            }
            onFail?.invoke(e as Exception)
        }
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                mimeType?.startsWith("image/") == true -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                mimeType?.startsWith("video/") == true -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                mimeType?.startsWith("audio/") == true -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
        } else {
            MediaStore.Files.getContentUri("external")
        }
    }

    private fun getRelativeForMimeType(mimeType: String): String {
        return when {
            mimeType.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            mimeType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
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

    private fun String.removePrefixIfStartsWith(prefix: String): String {
        return if (this.startsWith("$prefix/")) {
            this.replace("$prefix/", "")
        } else if (this.startsWith(prefix)) {
            this.replace(prefix, "")
        } else {
            this
        }
    }
}