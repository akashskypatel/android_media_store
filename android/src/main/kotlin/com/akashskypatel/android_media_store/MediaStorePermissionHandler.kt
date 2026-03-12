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
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import io.flutter.plugin.common.MethodChannel

class MediaStorePermissionHandler(private val activity: Activity) {
    
    companion object {
        const val REQUEST_CODE_MANAGE_MEDIA = 2024
    }

    fun canManageMedia(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaStore.canManageMedia(activity.applicationContext)
        } else {
            true
        }
    }

    fun requestManageMedia(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivityForResult(intent, REQUEST_CODE_MANAGE_MEDIA)
            result.success(true)
        } else {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            result.success(true)
        }
    }
}