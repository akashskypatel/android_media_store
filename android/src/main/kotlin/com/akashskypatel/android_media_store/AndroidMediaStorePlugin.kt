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
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** AndroidMediaStorePlugin */
class AndroidMediaStorePlugin : FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.ActivityResultListener {
    
    private lateinit var channel: MethodChannel
    private var applicationContext: Context? = null
    
    // Activity and Plugin bindings
    private var activityBinding: ActivityPluginBinding? = null
    private var mediaStore: AndroidMediaStore? = null
    private var permissionHandler: MediaStorePermissionHandler? = null

    companion object {
        private const val TAG = "AndroidMediaStorePlugin"
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "com.akashskypatel.android_media_store")
        channel.setMethodCallHandler(this)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        mediaStore = AndroidMediaStore(binding.activity)
        permissionHandler = MediaStorePermissionHandler(binding.activity)
        binding.addActivityResultListener(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        val store = mediaStore
        val context = applicationContext

        if (store == null || context == null) {
            result.error("NOT_INITIALIZED", "Plugin is not attached to an Activity yet.", null)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (call.method) {
                    "getPlatformVersion" -> {
                        withContext(Dispatchers.Main) { 
                            result.success("Android ${android.os.Build.VERSION.RELEASE}") 
                        }
                    }
                    
                    "isInitialized" -> {
                        val initialized = store.isInitialized()
                        withContext(Dispatchers.Main) { result.success(initialized) }
                    }

                    "pathToUri" -> {
                        val path = call.argument<String>("path")!!
                        val mime = call.argument<String?>("mimeType")
                        val uri = store.pathToUri(context, path, mime)?.toString()
                        withContext(Dispatchers.Main) { result.success(uri) }
                    }

                    "uriToPath" -> {
                        val uri = call.argument<String>("uri")!!
                        val path = store.uriToPath(context, Uri.parse(uri))
                        withContext(Dispatchers.Main) { result.success(path) }
                    }

                    "getReadableMediaFilePath" -> {
                        val pathOrUri = call.argument<String>("pathOrUri")!!
                        val path = store.getReadableMediaFilePath(context, pathOrUri)
                        withContext(Dispatchers.Main) { result.success(path) }
                    }

                    "readMediaFile" -> {
                        val pathOrUri = call.argument<String>("pathOrUri")!!
                        try {
                            val data = store.readMediaFile(context, pathOrUri)
                            withContext(Dispatchers.Main) { result.success(data) }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                if (e.message?.contains("FILE_TOO_LARGE") == true) {
                                    result.error("FILE_TOO_LARGE", "File is too large to transfer as bytes.", null)
                                } else {
                                    result.error("READ_ERROR", e.localizedMessage, null)
                                }
                            }
                        }
                    }

                    // ─── METHODS REQUIRING CONCURRENT CALLBACKS (Uses requestId) ───

                    "editMediaFile" -> {
                        val requestId = call.argument<String>("requestId")!!
                        val pathOrUri = call.argument<String>("pathOrUri")!!
                        val data = call.argument<ByteArray>("data")!!
                        val mime = call.argument<String?>("mimeType")
                        val res = store.editMediaFile(context, requestId, pathOrUri, data)
                        val returnData = if (res is Uri) res.toString() else res
                        withContext(Dispatchers.Main) { result.success(returnData) }
                    }

                    "deleteMediaFile" -> {
                        val requestId = call.argument<String>("requestId")!!
                        val pathOrUri = call.argument<String>("pathOrUri")!!
                        val success = store.deleteMediaFile(context, requestId, pathOrUri)
                        withContext(Dispatchers.Main) { result.success(success) }
                    }

                    "createMediaFileAtRelative" -> {
                        val requestId = call.argument<String>("requestId")!!
                        val displayName = call.argument<String>("displayName")!!
                        val relativePath = call.argument<String?>("relativePath")
                        val data = call.argument<ByteArray>("data")!!
                        val mime = call.argument<String?>("mimeType")
                        val res = store.createMediaFileAtRelative(context, requestId, displayName, relativePath, data, mime)
                        val returnData = if (res is Uri) res.toString() else res
                        withContext(Dispatchers.Main) { result.success(returnData) }
                    }

                    "createMediaFile" -> {
                        val requestId = call.argument<String>("requestId")!!
                        val displayName = call.argument<String>("displayName")!!
                        val data = call.argument<ByteArray>("data")!!
                        val mime = call.argument<String?>("mimeType")
                        val res = store.createMediaFile(context, requestId, displayName, data, mime)
                        val returnData = if (res is Uri) res.toString() else res
                        withContext(Dispatchers.Main) { result.success(returnData) }
                    }

                    "copyMediaFileToRelative" -> {
                        val requestId = call.argument<String>("requestId")!!
                        val pathOrUri = call.argument<String>("pathOrUri")!!
                        val displayName = call.argument<String>("displayName")!!
                        val relativePath = call.argument<String?>("relativePath")
                        val mime = call.argument<String?>("mimeType")
                        val res = store.copyMediaFileToRelative(context, requestId, displayName, pathOrUri, relativePath, mime)
                        val returnData = if (res is Uri) res.toString() else res
                        withContext(Dispatchers.Main) { result.success(returnData) }
                    }

                    "copyMediaFileToPathOrUri" -> {
                        val requestId = call.argument<String>("requestId")!!
                        val toPathOrUri = call.argument<String>("toPathOrUri")!!
                        val fromPathOrUri = call.argument<String>("fromPathOrUri")!!
                        val mime = call.argument<String?>("mimeType")
                        val res = store.copyMediaFileToPathOrUri(context, requestId, toPathOrUri, fromPathOrUri, mime)
                        val returnData = if (res is Uri) res.toString() else res
                        withContext(Dispatchers.Main) { result.success(returnData) }
                    }

                    "canManageMedia" -> {
                        result.success(permissionHandler?.canManageMedia() ?: false)
                    }
                    
                    "requestManageMedia" -> {
                        permissionHandler?.requestManageMedia(result)
                    }

                    else -> {
                        withContext(Dispatchers.Main) { result.notImplemented() }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "MethodCall ${call.method} failed", e)
                    result.error("MEDIA_STORE_ERROR", e.localizedMessage, null)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val store = mediaStore ?: return false

        // Check if the request is a known DELETE operation
        if (store.isPendingDelete(requestCode)) {
            CoroutineScope(Dispatchers.IO).launch {
                if (resultCode == Activity.RESULT_OK) {
                    val opResult = store.executePendingDeleteOperation(requestCode)
                    if (opResult != null) {
                        withContext(Dispatchers.Main) {
                            channel.invokeMethod("notifyDeleteComplete", mapOf(
                                "requestId" to opResult.first,
                                "success" to opResult.second
                            ))
                        }
                    }
                } else {
                    val reqId = store.cancelPendingDeleteOperation(requestCode)
                    if (reqId != null) {
                        withContext(Dispatchers.Main) {
                            channel.invokeMethod("notifyDeleteComplete", mapOf(
                                "requestId" to reqId,
                                "success" to false
                            ))
                        }
                    }
                }
            }
            return true
        }

        // Check if the request is a known WRITE operation
        if (store.isPendingWrite(requestCode)) {
            CoroutineScope(Dispatchers.IO).launch {
                if (resultCode == Activity.RESULT_OK) {
                    val opResult = store.executePendingWriteOperation(requestCode)
                    if (opResult != null) {
                        withContext(Dispatchers.Main) {
                            channel.invokeMethod("notifyCreateComplete", mapOf(
                                "requestId" to opResult.first,
                                "uri" to opResult.second?.toString()
                            ))
                        }
                    }
                } else {
                    val reqId = store.cancelPendingWriteOperation(requestCode)
                    if (reqId != null) {
                        withContext(Dispatchers.Main) {
                            channel.invokeMethod("notifyCreateComplete", mapOf(
                                "requestId" to reqId,
                                "uri" to null
                            ))
                        }
                    }
                }
            }
            return true
        }

        // Check if it's the Manage Media permission request
        if (requestCode == MediaStorePermissionHandler.REQUEST_CODE_MANAGE_MEDIA) {
            channel.invokeMethod("onManageMediaPermissionResult", permissionHandler?.canManageMedia())
            return true
        }

        return false // Return false if the request code doesn't belong to our plugin
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
        mediaStore = null
        permissionHandler = null
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        applicationContext = null
    }
}