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
        binding.addActivityResultListener(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        val store = mediaStore
        val context = applicationContext

        if (store == null || context == null) {
            result.error("NOT_INITIALIZED", "Plugin is not attached to an Activity yet.", null)
            return
        }

        // Run all MethodChannel calls on an IO Coroutine to prevent UI Thread ANR crashes
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

                    "editMediaFile" -> {
                        val pathOrUri = call.argument<String>("pathOrUri")!!
                        val data = call.argument<ByteArray>("data")!!
                        val mime = call.argument<String?>("mimeType")
                        val res = store.editMediaFile(context, pathOrUri, data)
                        // If it's a URI, convert to String. If it's "PENDING_AUTH", leave as String.
                        val returnData = if (res is Uri) res.toString() else res
                        withContext(Dispatchers.Main) { result.success(returnData) }
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

                    "deleteMediaFile" -> {
                        val pathOrUri = call.argument<String>("pathOrUri")!!
                        val success = store.deleteMediaFile(context, pathOrUri)
                        withContext(Dispatchers.Main) { result.success(success) }
                    }

                    "createMediaFileAtRelative" -> {
                        val displayName = call.argument<String>("displayName")!!
                        val relativePath = call.argument<String?>("relativePath")
                        val data = call.argument<ByteArray>("data")!!
                        val mime = call.argument<String?>("mimeType")
                        val res = store.createMediaFileAtRelative(context, displayName, relativePath, data, mime)
                        val returnData = if (res is Uri) res.toString() else res
                        withContext(Dispatchers.Main) { result.success(returnData) }
                    }

                    "createMediaFile" -> {
                        val displayName = call.argument<String>("displayName")!!
                        val data = call.argument<ByteArray>("data")!!
                        val mime = call.argument<String?>("mimeType")
                        val res = store.createMediaFile(context, displayName, data, mime)
                        val returnData = if (res is Uri) res.toString() else res
                        withContext(Dispatchers.Main) { result.success(returnData) }
                    }

                    "copyMediaFileToRelative" -> {
                        val pathOrUri = call.argument<String>("pathOrUri")!!
                        val displayName = call.argument<String>("displayName")!!
                        val relativePath = call.argument<String?>("relativePath")
                        val mime = call.argument<String?>("mimeType")
                        val res = store.copyMediaFileToRelative(context, displayName, pathOrUri, relativePath, mime)
                        val returnData = if (res is Uri) res.toString() else res
                        withContext(Dispatchers.Main) { result.success(returnData) }
                    }

                    "copyMediaFileToPathOrUri" -> {
                        val toPathOrUri = call.argument<String>("toPathOrUri")!!
                        val fromPathOrUri = call.argument<String>("fromPathOrUri")!!
                        val mime = call.argument<String?>("mimeType")
                        val res = store.copyMediaFileToPathOrUri(context, toPathOrUri, fromPathOrUri, mime)
                        val returnData = if (res is Uri) res.toString() else res
                        withContext(Dispatchers.Main) { result.success(returnData) }
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

        when (requestCode) {
            store.DELETE_REQUEST_CODE -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        when (resultCode) {
                            Activity.RESULT_OK -> {
                                val actualSuccess = store.executePendingDeleteOperations()
                                withContext(Dispatchers.Main) {
                                    channel.invokeMethod(store.DELETE_REQUEST_NOTIFY, actualSuccess)
                                }
                            }
                            else -> {
                                withContext(Dispatchers.Main) {
                                    channel.invokeMethod(store.DELETE_REQUEST_NOTIFY, false)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            channel.invokeMethod(store.DELETE_REQUEST_NOTIFY, false)
                        }
                    }
                }
                return true
            }

            store.WRITE_REQUEST_CODE -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        when (resultCode) {
                            Activity.RESULT_OK -> {
                                val uris = store.executePendingWriteOperations()
                                val uriStrings = uris?.map { it.toString() }
                                withContext(Dispatchers.Main) {
                                    channel.invokeMethod(store.WRITE_REQUEST_NOTIFY, uriStrings)
                                }
                            }
                            else -> {
                                withContext(Dispatchers.Main) {
                                    channel.invokeMethod(store.WRITE_REQUEST_NOTIFY, null)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            channel.invokeMethod(store.WRITE_REQUEST_NOTIFY, null)
                        }
                    }
                }
                return true
            }
        }
        return false // Return false if the request code doesn't belong to our plugin
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
        mediaStore = null
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
        mediaStore = null
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        applicationContext = null
    }
}