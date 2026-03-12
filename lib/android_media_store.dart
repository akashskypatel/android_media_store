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

import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// A custom exception thrown when a native MediaStore operation fails.
///
/// This encompasses issues like:
/// * Lack of permissions to access a specific file.
/// * The user cancelling a system-provided permission dialog.
/// * Hardware or OS-level errors during file I/O.
/// * File size limits exceeded (e.g., 1MB limit for direct memory reads).
class AndroidMediaStoreException implements Exception {
  AndroidMediaStoreException(this.code, this.message);

  /// The error code returned from the native platform (e.g., 'PERMISSION_DENIED', 'FILE_TOO_LARGE').
  final String code;

  /// The descriptive error message.
  final String? message;

  @override
  String toString() => 'AndroidMediaStoreException($code): $message';
}

/// A helper class to manage asynchronous media operations that may require
///
/// On Android 11+ (API 30+), many MediaStore operations (like editing or deleting
/// files owned by other apps) require a system-level confirmation dialog. This class
/// bridges the gap between the initial method call and the eventual callback from
/// user permission via OS-level dialogs (e.g., Android 11+ Scoped Storage).
class MediaOperation<T> {
  MediaOperation();
  final ValueNotifier<Completer<T>?> _completer = ValueNotifier(null);
  
  void Function(T)? _onSuccess;
  void Function(Exception)? _onFail;
  Timer? resetTimer;

  Completer<T>? get completer => _completer.value;

  /// Resets the operation state, optionally throwing an error to the waiting Future and onFail callback.
  ///
  /// This is used for cleanup when a request is cancelled or times out.
  void reset({Exception? error}) {
    final failCb = _onFail;
    
    _onSuccess = null;
    _onFail = null;
    resetTimer?.cancel();
    resetTimer = null;

    if (error != null && !(completer?.isCompleted ?? true)) {
      completer?.completeError(error);
      if (failCb != null) failCb(error);
    }
  }

  /// Initiates a new operation with a timeout and callbacks.
  ///
  /// If a previous operation is still active, it will be cancelled with a [TimeoutException]
  /// to prevent resource leaks and state confusion.
  void set(
    Completer<T> newCompleter, {
    void Function(T)? onSuccess,
    void Function(Exception)? onFail,
    Duration timeout = const Duration(minutes: 3),
  }) {
    if (completer != null && !completer!.isCompleted) {
      reset(error: TimeoutException('Previous request overridden or timed out'));
    }
    reset();
    _completer.value = newCompleter;
    _onSuccess = onSuccess;
    _onFail = onFail;
    resetTimer = Timer(timeout, () {
      reset(error: TimeoutException('Request timed out waiting for user permission'));
    });
  }

  /// Completes the operation successfully, triggering the onSuccess callback.
  ///
  /// Cancels any active timeout timers and clears the internal callback references.
  void complete(T result) {
    final successCb = _onSuccess;
    
    _onSuccess = null;
    _onFail = null;
    resetTimer?.cancel();
    resetTimer = null;

    if (!(completer?.isCompleted ?? true)) {
      completer?.complete(result);
      if (successCb != null) successCb(result);
    }
  }

  /// Completes the operation with an error, triggering the onFail callback.
  void completeError(Exception error) {
    reset(error: error);
  }
}

/// A utility class bridging Flutter to Android's MediaStore and Scoped Storage APIs.
///
/// This singleton provides a modern, high-level API for interacting with media files
/// while abstracting the complexities of Android 10+ (API 29+) Scoped Storage and
/// the 1MB Binder transaction limit.
///
/// Key responsibilities:
/// * Mapping between `content://` URIs and physical paths.
/// * Managing asynchronous permission dialogs for file modifications.
/// * Handling OOM prevention via streaming for large files.
class AndroidMediaStore {
  AndroidMediaStore._();

  /// The singleton instance of [AndroidMediaStore].
  static final AndroidMediaStore instance = AndroidMediaStore._();

  /// The underlying MethodChannel communicating with the native Android code.
  static const mediaChannel = MethodChannel(
    'com.akashskypatel.android_media_store',
  );

  static final StreamController<bool> _permissionStreamController =
      StreamController<bool>.broadcast();

  /// A broadcast stream that emits the status of the `MANAGE_MEDIA` special permission.
  ///
  /// Listen to this to react when a user returns from the system settings screen.
  Stream<bool> get onManageMediaPermissionChanged =>
      _permissionStreamController.stream;

  static bool initialized = false;

  // Registry for pending concurrent operations
  static final Map<String, dynamic> _pendingOperations = {};
  static int _requestIdCounter = 0;

  /// Generates a unique request ID to track concurrent native operations.
  static String _generateRequestId() {
    _requestIdCounter++;
    return '${DateTime.now().millisecondsSinceEpoch}_$_requestIdCounter';
  }

  /// Retrieves the current Android SDK version string.
  Future<String?> getPlatformVersion() async {
    if (!Platform.isAndroid) return null;
    return await mediaChannel.invokeMethod('getPlatformVersion');
  }

  /// Ensures the MethodChannel and its native callbacks are properly registered.
  ///
  /// This method sets up the `notifyCreateComplete` and `notifyDeleteComplete` listeners.
  /// Safe to call multiple times.
  static Future<void> ensureInitialized() async {
    if (!Platform.isAndroid) return;
    if (initialized) return;

    bool? channelInitialized;
    while (channelInitialized == null) {
      try {
        channelInitialized = await isChannelInitialized();
      } catch (_) {}
    }

    mediaChannel.setMethodCallHandler((event) async {
      switch (event.method) {
        case 'notifyDeleteComplete':
          final args = Map<String, dynamic>.from(event.arguments as Map);
          final String reqId = args['requestId'] as String;
          final bool success = args['success'] == true;

          final operation = _pendingOperations.remove(reqId) as MediaOperation<bool>?;
          if (operation != null) {
            if (success) {
              operation.complete(true);
            } else {
              operation.completeError(
                AndroidMediaStoreException('FAILED', 'Delete operation failed or was cancelled.')
              );
            }
          }
          break;

        case 'notifyCreateComplete':
          final args = Map<String, dynamic>.from(event.arguments as Map);
          final String reqId = args['requestId'] as String;
          final String? uri = args['uri'] as String?;

          final operation = _pendingOperations.remove(reqId) as MediaOperation<String?>?;
          if (operation != null) {
            if (uri == null) {
              operation.completeError(
                AndroidMediaStoreException('CANCELLED', 'Operation cancelled by user or failed.')
              );
            } else {
              operation.complete(uri);
            }
          }
          break;

        case 'onManageMediaPermissionResult':
          final bool isGranted = event.arguments == true;
          _permissionStreamController.add(isGranted);
          debugPrint('Manage Media permission status changed: $isGranted');
          break;

        default:
      }
    });
    initialized = true;
  }

  /// Checks if the native MethodChannel is ready to accept commands.
  ///
  /// Returns `true` if the native plugin side has finished its setup.
  /// Throws [AndroidMediaStoreException] if the channel is unreachable.
  static Future<bool?> isChannelInitialized() async {
    if (!Platform.isAndroid) return false;
    try {
      return await mediaChannel.invokeMethod('isInitialized');
    } on PlatformException catch (e) {
      throw AndroidMediaStoreException(e.code, e.message);
    }
  }

  /// Converts a file system path to a MediaStore or FileProvider compatible URI.
  ///
  /// Providing a [mimeType] (e.g., 'image/jpeg') significantly improves the
  /// lookup accuracy and speed. Returns `null` if the path cannot be mapped.
  Future<String?> pathToUri(String path, {String? mimeType}) async {
    if (!Platform.isAndroid) return null;
    try {
      await ensureInitialized();
      return await mediaChannel.invokeMethod('pathToUri', {
        'path': path,
        'mimeType': mimeType,
      });
    } on PlatformException catch (e) {
      throw AndroidMediaStoreException(e.code, e.message);
    }
  }

  /// Converts a MediaStore or FileProvider URI back to a file system path.
  ///
  /// Note: On Android 10+, returned paths may have restricted access.
  /// Use [getReadableMediaFilePath] for reliable read access.
  Future<String?> uriToPath(String uri) async {
    if (!Platform.isAndroid) return null;
    try {
      await ensureInitialized();
      return await mediaChannel.invokeMethod('uriToPath', {'uri': uri});
    } on PlatformException catch (e) {
      throw AndroidMediaStoreException(e.code, e.message);
    }
  }

  /// Safely resolves a media file into a physical, readable file path.
  ///
  /// If the input is a `content://` URI, the plugin will stream the content
  /// into a temporary file in the app's cache directory. This is the **preferred
  /// way to handle large files (Videos, Hi-Res Images)** to avoid memory crashes.
  ///
  /// **Warning:** You are responsible for deleting the file at the returned path
  /// once you are finished with it to prevent storage bloat.
  Future<String?> getReadableMediaFilePath(String pathOrUri) async {
    if (!Platform.isAndroid) return null;
    try {
      await ensureInitialized();
      return await mediaChannel.invokeMethod('getReadableMediaFilePath', {
        'pathOrUri': pathOrUri,
      });
    } on PlatformException catch (e) {
      throw AndroidMediaStoreException(e.code, e.message);
    }
  }

  /// Reads the complete binary content of a media file.
  ///
  /// **Performance Note:** This method has a built-in safety limit of 1MB.
  /// If the file is larger than 1MB, it automatically falls back to the
  /// streaming/cache method ([getReadableMediaFilePath]) to prevent
  /// `TransactionTooLargeException` and `OutOfMemoryError`.
  ///
  /// Returns a [Uint8List] of the file content, or `null` if the file is inaccessible.
  Future<Uint8List?> readMediaFile(String pathOrUri, {String? mimeType}) async {
    if (!Platform.isAndroid) return null;
    try {
      await ensureInitialized();
      return await mediaChannel.invokeMethod<Uint8List>('readMediaFile', {
        'pathOrUri': pathOrUri,
        'mimeType': mimeType,
      });
    } on PlatformException catch (e) {
      if (e.code == 'FILE_TOO_LARGE') {
        debugPrint('File > 1MB. Falling back to stream reading...');
        final cachedPath = await getReadableMediaFilePath(pathOrUri);
        if (cachedPath != null) {
          final file = File(cachedPath);
          final bytes = await file.readAsBytes();
          if (await file.exists()) await file.delete();
          return bytes;
        }
        return null;
      }
      throw AndroidMediaStoreException(e.code, e.message);
    }
  }

  // ---------------------------------------------------------------------------
  // CONCURRENT WRITE & DELETE OPERATIONS
  // ---------------------------------------------------------------------------

  /// Overwrites an existing media file with new binary data.
  ///
  /// If the app does not have write access to the file (common in Scoped Storage),
  /// this will automatically trigger a system permission dialog.
  ///
  /// * [onSuccess]: Called with the URI of the edited file.
  /// * [onFail]: Called if the user denies permission or an I/O error occurs.
  Future<String?> editMediaFile(
    String pathOrUri,
    List<int> data, {
    String? mimeType,
    void Function(String?)? onSuccess,
    void Function(Exception)? onFail,
  }) async {
    if (!Platform.isAndroid) return null;
    await ensureInitialized();
    final reqId = _generateRequestId();
    final operation = MediaOperation<String?>();
    _pendingOperations[reqId] = operation;
    
    try {
      operation.set(Completer<String?>(), onSuccess: onSuccess, onFail: onFail);

      final result = await mediaChannel.invokeMethod('editMediaFile', {
        'requestId': reqId,
        'pathOrUri': pathOrUri,
        'data': Uint8List.fromList(data),
        'mimeType': mimeType,
      });

      if (result == 'PENDING_AUTH') {
        return await operation.completer!.future;
      }

      // Handled synchronously by Native
      _pendingOperations.remove(reqId);
      final String? uri = result?.toString();
      operation.complete(uri);
      return uri;

    } on PlatformException catch (e) {
      _pendingOperations.remove(reqId);
      final ex = AndroidMediaStoreException(e.code, e.message);
      operation.completeError(ex);
      rethrow;
    } catch (e) {
      _pendingOperations.remove(reqId);
      final ex = AndroidMediaStoreException('UNKNOWN', e.toString());
      operation.completeError(ex);
      rethrow;
    }
  }

  /// Permanently deletes a media file from storage.
  ///
  /// On Android 11+, if the file is not owned by the app, a system dialog
  /// will appear asking the user to confirm the deletion.
  ///
  /// Returns `true` if the file was deleted successfully.
  Future<bool> deleteMediaFile(
    String pathOrUri, {
    void Function(bool)? onSuccess,
    void Function(Exception)? onFail,
  }) async {
    if (!Platform.isAndroid) return false;
    await ensureInitialized();
    final reqId = _generateRequestId();
    final operation = MediaOperation<bool>();
    _pendingOperations[reqId] = operation;

    try {
      operation.set(Completer<bool>(), onSuccess: onSuccess, onFail: onFail);

      final result = await mediaChannel.invokeMethod('deleteMediaFile', {
        'requestId': reqId,
        'pathOrUri': pathOrUri,
      });

      if (result == 'PENDING_AUTH') {
        return await operation.completer!.future;
      }

      // Handled synchronously
      _pendingOperations.remove(reqId);
      final bool success = result == true;
      operation.complete(success);
      return success;

    } on PlatformException catch (e) {
      _pendingOperations.remove(reqId);
      final ex = AndroidMediaStoreException(e.code, e.message);
      operation.completeError(ex);
      rethrow;
    } catch (e) {
      _pendingOperations.remove(reqId);
      final ex = AndroidMediaStoreException('UNKNOWN', e.toString());
      operation.completeError(ex);
      rethrow;
    }
  }

  /// Creates a new media file in a specific MediaStore relative directory.
  ///
  /// [relativePath] should be a standard Android directory like `Documents/` or `Download/`.
  /// Subdirectories are supported (e.g., `Documents/MyProject/`).
  ///
  /// This method handles the creation of the MediaStore entry and writes the
  /// initial [data] to it. Returns the `content://` URI of the new file.
  /// Throws [AndroidMediaStoreException] if the directory is restricted.
  Future<String?> createMediaFileAtRelative(
    String displayName,
    String relativePath,
    List<int> data, {
    String? mimeType,
    void Function(String?)? onSuccess,
    void Function(Exception)? onFail,
  }) async {
    if (!Platform.isAndroid) return null;
    await ensureInitialized();
    final reqId = _generateRequestId();
    final operation = MediaOperation<String?>();
    _pendingOperations[reqId] = operation;

    try {
      operation.set(Completer<String?>(), onSuccess: onSuccess, onFail: onFail);

      final result = await mediaChannel.invokeMethod('createMediaFileAtRelative', {
        'requestId': reqId,
        'displayName': displayName,
        'relativePath': relativePath,
        'data': Uint8List.fromList(data),
        'mimeType': mimeType,
      });

      if (result == 'PENDING_AUTH') {
        return await operation.completer!.future;
      }

      _pendingOperations.remove(reqId);
      final String? uri = result?.toString();
      operation.complete(uri);
      return uri;

    } on PlatformException catch (e) {
      _pendingOperations.remove(reqId);
      final ex = AndroidMediaStoreException(e.code, e.message);
      operation.completeError(ex);
      rethrow;
    } catch (e) {
      _pendingOperations.remove(reqId);
      final ex = AndroidMediaStoreException('UNKNOWN', e.toString());
      operation.completeError(ex);
      rethrow;
    }
  }

  /// Creates a new media file with automatic directory selection.
  ///
  /// The plugin will automatically choose the best destination based on the [mimeType].
  /// For example, `image/jpeg` will go to `Pictures/`, and `audio/mpeg` will go to `Music/`.
  ///
  /// Returns the `content://` URI of the newly created file.
  Future<String?> createMediaFile(
    String displayName,
    List<int> data, {
    String? mimeType,
    void Function(String?)? onSuccess,
    void Function(Exception)? onFail,
  }) async {
    if (!Platform.isAndroid) return null;
    await ensureInitialized();
    final reqId = _generateRequestId();
    final operation = MediaOperation<String?>();
    _pendingOperations[reqId] = operation;

    try {
      operation.set(Completer<String?>(), onSuccess: onSuccess, onFail: onFail);

      final result = await mediaChannel.invokeMethod('createMediaFile', {
        'requestId': reqId,
        'displayName': displayName,
        'data': Uint8List.fromList(data),
        'mimeType': mimeType,
      });

      if (result == 'PENDING_AUTH') {
        return await operation.completer!.future;
      }

      _pendingOperations.remove(reqId);
      final String? uri = result?.toString();
      operation.complete(uri);
      return uri;

    } on PlatformException catch (e) {
      _pendingOperations.remove(reqId);
      final ex = AndroidMediaStoreException(e.code, e.message);
      operation.completeError(ex);
      rethrow;
    } catch (e) {
      _pendingOperations.remove(reqId);
      final ex = AndroidMediaStoreException('UNKNOWN', e.toString());
      operation.completeError(ex);
      rethrow;
    }
  }

  /// Copies an existing media file to a new location in a specific relative directory.
  ///
  /// This is an efficient way to duplicate files within the MediaStore.
  /// Permission checks are performed automatically.
  ///
  /// Returns the URI of the new copy.
  Future<String?> copyMediaFileToRelative(
    String pathOrUri,
    String displayName, {
    String? relativePath,
    String? mimeType,
    void Function(String?)? onSuccess,
    void Function(Exception)? onFail,
  }) async {
    if (!Platform.isAndroid) return null;
    await ensureInitialized();
    final reqId = _generateRequestId();
    final operation = MediaOperation<String?>();
    _pendingOperations[reqId] = operation;

    try {
      operation.set(Completer<String?>(), onSuccess: onSuccess, onFail: onFail);

      final result = await mediaChannel.invokeMethod('copyMediaFileToRelative', {
        'requestId': reqId,
        'pathOrUri': pathOrUri,
        'displayName': displayName,
        'relativePath': relativePath,
        'mimeType': mimeType,
      });

      if (result == 'PENDING_AUTH') {
        return await operation.completer!.future;
      }

      _pendingOperations.remove(reqId);
      final String? uri = result?.toString();
      operation.complete(uri);
      return uri;

    } on PlatformException catch (e) {
      _pendingOperations.remove(reqId);
      final ex = AndroidMediaStoreException(e.code, e.message);
      operation.completeError(ex);
      rethrow;
    } catch (e) {
      _pendingOperations.remove(reqId);
      final ex = AndroidMediaStoreException('UNKNOWN', e.toString());
      operation.completeError(ex);
      rethrow;
    }
  }

  /// Copies a media file to a specific destination path or URI.
  ///
  /// Use this when you have a specific target destination that might not conform
  /// to standard relative paths.
  ///
  /// Returns the URI of the resulting copy.
  Future<String?> copyMediaFileToPathOrUri(
    String toPathOrUri,
    String fromPathOrUri, {
    String? mimeType,
    void Function(String?)? onSuccess,
    void Function(Exception)? onFail,
  }) async {
    if (!Platform.isAndroid) return null;
    await ensureInitialized();
    final reqId = _generateRequestId();
    final operation = MediaOperation<String?>();
    _pendingOperations[reqId] = operation;

    try {
      operation.set(Completer<String?>(), onSuccess: onSuccess, onFail: onFail);

      final result = await mediaChannel.invokeMethod('copyMediaFileToPathOrUri', {
        'requestId': reqId,
        'toPathOrUri': toPathOrUri,
        'fromPathOrUri': fromPathOrUri,
        'mimeType': mimeType,
      });

      if (result == 'PENDING_AUTH') {
        return await operation.completer!.future;
      }

      _pendingOperations.remove(reqId);
      final String? uri = result?.toString();
      operation.complete(uri);
      return uri;

    } on PlatformException catch (e) {
      _pendingOperations.remove(reqId);
      final ex = AndroidMediaStoreException(e.code, e.message);
      operation.completeError(ex);
      rethrow;
    } catch (e) {
      _pendingOperations.remove(reqId);
      final ex = AndroidMediaStoreException('UNKNOWN', e.toString());
      operation.completeError(ex);
      rethrow;
    }
  }

  /// Checks if the app has been granted the special media management access.
  ///
  /// On Android 12+ (API 31+), apps with this permission can modify or delete
  /// media files without showing a confirmation dialog for every single file.
  /// Returns `true` on older Android versions as the permission is not applicable.
  Future<bool> canManageMedia() async {
    if (!Platform.isAndroid) return false;
    try {
      await ensureInitialized();
      return await mediaChannel.invokeMethod('canManageMedia') ?? false;
    } on PlatformException catch (e) {
      throw AndroidMediaStoreException(e.code, e.message);
    }
  }

  /// Redirects the user to the system settings screen to grant "Manage Media" access.
  ///
  /// You should usually call [canManageMedia] first. Since this opens a system UI,
  /// use [onManageMediaPermissionChanged] to be notified when the user grants
  /// the permission and returns to your app.
  Future<void> requestManageMedia() async {
    if (!Platform.isAndroid) return;
    try {
      await ensureInitialized();
      await mediaChannel.invokeMethod('requestManageMedia');
    } on PlatformException catch (e) {
      throw AndroidMediaStoreException(e.code, e.message);
    }
  }

  void dispose() {
    _permissionStreamController.close();
  }
}