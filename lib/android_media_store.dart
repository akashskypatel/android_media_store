/*
 *     Copyright (C) 2025 Akash Patel
 *
 *     Reverbio is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Reverbio is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 *
 *     For more information about Reverbio, including how to contribute,
 *     please visit: https://github.com/akashskypatel/Reverbio
 */

import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// A custom exception thrown when a native AndroidMediaStore operation fails.
class AndroidMediaStoreException implements Exception {
  AndroidMediaStoreException(this.code, this.message);

  /// The error code returned from the native platform.
  final String code;

  /// The descriptive error message.
  final String? message;

  @override
  String toString() => 'AndroidMediaStoreException($code): $message';
}

/// A helper class to manage asynchronous media operations that may require
/// user permission via OS-level dialogs (e.g., Android 11+ Scoped Storage).
class MediaOperation<T> {
  MediaOperation();
  final ValueNotifier<Completer<T>?> _completer = ValueNotifier(null);
  Function? _onComplete;
  Timer? resetTimer;
  Function? get onComplete => _onComplete;
  Completer<T>? get completer => _completer.value;

  /// Resets the operation state, optionally throwing an error to the waiting Future.
  void reset({dynamic error}) {
    _onComplete = null;
    resetTimer?.cancel();
    resetTimer = null;
    if (error != null && !(completer?.isCompleted ?? true)) {
      completer?.completeError(error);
    }
  }

  /// Initiates a new operation with a timeout.
  /// The timeout is generous (3 minutes) to allow users to read and interact
  /// with Android's permission dialogs.
  void set(
    Completer<T> newCompleter,
    Function? onCompleteCallback, {
    Duration timeout = const Duration(minutes: 3),
  }) {
    if (completer != null && !completer!.isCompleted) {
      reset(
        error: TimeoutException('Previous request overridden or timed out'),
      );
    }
    reset();
    _completer.value = newCompleter;
    _onComplete = onCompleteCallback;
    resetTimer = Timer(timeout, () {
      reset(
        error: TimeoutException(
          'Request timed out waiting for user permission',
        ),
      );
    });
  }
}

/// A utility class bridging Flutter to Android's MediaStore and Scoped Storage APIs.
/// It provides safe, modern methods to create, read, edit, and delete media files
/// while adhering to Android 10+ storage restrictions.
class AndroidMediaStore {
  AndroidMediaStore._();

  /// The singleton instance of [AndroidMediaStore].
  static final AndroidMediaStore instance = AndroidMediaStore._();

  /// The underlying MethodChannel communicating with the native Android code.
  static const mediaChannel = MethodChannel(
    'com.akashskypatel.android_media_store',
  );

  static bool initialized = false;
  static final MediaOperation<bool> _deleteOperation = MediaOperation();
  static final MediaOperation<String?> _createOperation = MediaOperation();

  Future<String?> getPlatformVersion() async {
    return await mediaChannel.invokeMethod('getPlatformVersion');
  }

  /// Ensures the MethodChannel and its native callbacks are properly registered.
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
          if (_deleteOperation.completer != null &&
              !_deleteOperation.completer!.isCompleted) {
            // Null-safe handling (null means user cancelled or operation failed)
            final bool success = event.arguments == true;
            _deleteOperation.completer!.complete(success);
            if (_deleteOperation.onComplete != null) {
              _deleteOperation.onComplete!(success);
            }
          }
          break;

        case 'notifyCreateComplete':
          if (_createOperation.completer != null &&
              !_createOperation.completer!.isCompleted) {
            if (event.arguments == null) {
              _createOperation.completer!.completeError(
                AndroidMediaStoreException(
                  'CANCELLED',
                  'Operation cancelled by user or failed.',
                ),
              );
            } else {
              final list = event.arguments as List;
              final String? uri = list.isNotEmpty
                  ? list.first.toString()
                  : null;
              _createOperation.completer!.complete(uri);
              if (_createOperation.onComplete != null) {
                _createOperation.onComplete!(uri);
              }
            }
          }
          break;
        default:
      }
    });
    initialized = true;
  }

  /// Checks if the native MethodChannel is ready to accept commands.
  static Future<bool?> isChannelInitialized() async {
    try {
      return await mediaChannel.invokeMethod('isInitialized');
    } on PlatformException catch (e) {
      throw AndroidMediaStoreException(e.code, e.message);
    }
  }

  /// Converts a file system path to a MediaStore or FileProvider compatible URI.
  ///
  /// This method handles conversion for both existing media files and new files,
  /// supporting various storage locations including app-specific directories,
  /// external storage, and MediaStore collections.
  ///
  /// Parameters:
  /// - [path]: The absolute file system path to convert.
  /// - [mimeType]: Optional MIME type to improve URI resolution accuracy.
  ///
  /// Returns a content URI string, or `null` if the path cannot be resolved.
  Future<String?> pathToUri(String path, {String? mimeType}) async {
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
  /// Note: Some URIs (like those from the Storage Access Framework) may not
  /// have a direct file path representation on modern Android versions.
  ///
  /// Parameters:
  /// - [uri]: The content URI to resolve to a file path.
  ///
  /// Returns the absolute file system path, or `null` if no path exists.
  Future<String?> uriToPath(String uri) async {
    try {
      await ensureInitialized();
      return await mediaChannel.invokeMethod('uriToPath', {'uri': uri});
    } on PlatformException catch (e) {
      throw AndroidMediaStoreException(e.code, e.message);
    }
  }

  /// Overwrites an existing media file with new binary data.
  ///
  /// This operation handles Android 11+ Scoped Storage restrictions. If the app
  /// doesn't own the target file, it will automatically prompt the user for permission.
  ///
  /// Parameters:
  /// - [pathOrUri]: Either a content URI or file path of the media file.
  /// - [data]: The new binary content to write.
  /// - [mimeType]: Optional MIME type for path-based operations.
  /// - [onComplete]: Optional callback executed when the operation finishes.
  ///
  /// Returns the URI of the successfully edited file.
  Future<String?> editMediaFile(
    String pathOrUri,
    List<int> data, {
    String? mimeType,
    Function? onComplete,
  }) async {
    try {
      await ensureInitialized();
      _createOperation.set(Completer(), onComplete);

      final result = await mediaChannel.invokeMethod('editMediaFile', {
        'pathOrUri': pathOrUri,
        'data': Uint8List.fromList(data),
        'mimeType': mimeType,
      });

      // If native code requires user authorization, wait for the callback.
      // Otherwise, the write happened synchronously (e.g., app owns the file).
      if (result == 'PENDING_AUTH') {
        return await _createOperation.completer!.future;
      }

      _createOperation.reset();
      return result?.toString();
    } on PlatformException catch (e) {
      throw AndroidMediaStoreException(e.code, e.message);
    }
  }

  /// Safely resolves a media file into a physical, readable file path.
  ///
  /// If the target is a `content://` URI, this method streams the content into
  /// a temporary file in the app's cache directory and returns that cache path.
  /// This bypasses Android's 1MB Binder transaction limit and prevents memory exhaustion.
  ///
  /// **Note:** You should delete the returned file using `File(path).delete()`
  /// when you are finished processing it to free up disk space.
  Future<String?> getReadableMediaFilePath(String pathOrUri) async {
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
  /// **Smart Fallback:** This method initially attempts to read the bytes directly
  /// via memory for maximum speed. If the file is larger than 1MB (which would
  /// cause an Android IPC crash), it automatically falls back to safely caching
  /// the file to disk, reading it, and cleaning up the cache.
  ///
  /// Parameters:
  /// -[pathOrUri]: Either a content URI or file path of the media file.
  /// - [mimeType]: Optional MIME type for path-based operations.
  ///
  /// Returns the file content as bytes, or `null` if the file doesn't exist.
  Future<Uint8List?> readMediaFile(String pathOrUri, {String? mimeType}) async {
    try {
      await ensureInitialized();
      return await mediaChannel.invokeMethod<Uint8List>('readMediaFile', {
        'pathOrUri': pathOrUri,
        'mimeType': mimeType,
      });
    } on PlatformException catch (e) {
      // Fallback: If native code rejects the direct memory transfer due to size,
      // we ask native to safely stream it to a cache file instead.
      if (e.code == 'FILE_TOO_LARGE') {
        debugPrint('File > 1MB. Falling back to stream reading...');
        final cachedPath = await getReadableMediaFilePath(pathOrUri);
        if (cachedPath != null) {
          final file = File(cachedPath);
          final bytes = await file.readAsBytes();

          // Clean up the temporary cache file to free space
          if (await file.exists()) await file.delete();

          return bytes;
        }
        return null;
      }
      throw AndroidMediaStoreException(e.code, e.message);
    }
  }

  /// Permanently deletes a media file from storage.
  ///
  /// On Android 11+ (API 30+), deleting files owned by other apps will trigger
  /// a system permission dialog. The operation handles this asynchronously.
  ///
  /// **Warning:** This operation is irreversible.
  ///
  /// Returns `true` if the delete operation completed successfully.
  Future<bool> deleteMediaFile(String pathOrUri, {Function? onComplete}) async {
    try {
      await ensureInitialized();
      _deleteOperation.set(Completer(), onComplete);

      final result = await mediaChannel.invokeMethod('deleteMediaFile', {
        'pathOrUri': pathOrUri,
      });

      if (result == 'PENDING_AUTH') {
        return await _deleteOperation.completer!.future;
      }

      _deleteOperation.reset();
      return result == true;
    } catch (e, stackTrace) {
      _deleteOperation.completer?.completeError(
        AndroidMediaStoreException(e.toString(), stackTrace.toString()),
      );
      rethrow;
    }
  }

  /// Creates a new media file in a specific MediaStore relative directory.
  ///
  /// Example target directories: `Environment.DIRECTORY_MUSIC` ("Music/"),
  /// `Environment.DIRECTORY_PICTURES` ("Pictures/").
  ///
  /// Parameters:
  /// - [displayName]: The filename including extension (e.g., "song.mp3").
  /// - [relativePath]: The target MediaStore directory (e.g., "Music/").
  /// - [data]: The binary content of the new file.
  /// - [mimeType]: Optional MIME type of the file content.
  ///
  /// Returns the content URI of the newly created file.
  Future<String?> createMediaFileAtRelative(
    String displayName,
    String relativePath,
    List<int> data, {
    String? mimeType,
    Function? onComplete,
  }) async {
    try {
      await ensureInitialized();
      _createOperation.set(Completer(), onComplete);

      final result = await mediaChannel
          .invokeMethod('createMediaFileAtRelative', {
            'displayName': displayName,
            'relativePath': relativePath,
            'data': Uint8List.fromList(data),
            'mimeType': mimeType,
          });

      if (result == 'PENDING_AUTH') {
        return await _createOperation.completer!.future;
      }

      _createOperation.reset();
      return result?.toString();
    } catch (e, stackTrace) {
      _createOperation.completer?.completeError(
        AndroidMediaStoreException(e.toString(), stackTrace.toString()),
      );
      rethrow;
    }
  }

  /// Creates a new media file with automatic directory selection.
  ///
  /// The target directory is automatically determined based on the MIME type
  /// (e.g., Audio -> "Music/", Images -> "Pictures/").
  ///
  /// Parameters:
  /// - [displayName]: The filename including extension (e.g., "photo.jpg").
  /// -[data]: The binary content of the new file.
  /// - [mimeType]: Optional MIME type used for directory selection.
  ///
  /// Returns the content URI of the newly created file.
  Future<String?> createMediaFile(
    String displayName,
    List<int> data, {
    String? mimeType,
    Function? onComplete,
  }) async {
    try {
      await ensureInitialized();
      _createOperation.set(Completer(), onComplete);

      final result = await mediaChannel.invokeMethod('createMediaFile', {
        'displayName': displayName,
        'data': Uint8List.fromList(data),
        'mimeType': mimeType,
      });

      if (result == 'PENDING_AUTH') {
        return await _createOperation.completer!.future;
      }

      _createOperation.reset();
      return result?.toString();
    } catch (e, stackTrace) {
      _createOperation.completer?.completeError(
        AndroidMediaStoreException(e.toString(), stackTrace.toString()),
      );
      rethrow;
    }
  }

  /// Copies an existing media file to a new location in a specific relative directory.
  ///
  /// The original file remains unchanged. If the destination requires permission
  /// (Android 11+), it will prompt the user automatically.
  ///
  /// Returns the Content URI of the newly created copy.
  Future<String?> copyMediaFileToRelative(
    String pathOrUri,
    String displayName, {
    String? relativePath,
    String? mimeType,
    Function? onComplete,
  }) async {
    try {
      await ensureInitialized();
      _createOperation.set(Completer(), onComplete);

      final result = await mediaChannel
          .invokeMethod('copyMediaFileToRelative', {
            'pathOrUri': pathOrUri,
            'displayName': displayName,
            'relativePath': relativePath,
            'mimeType': mimeType,
          });

      if (result == 'PENDING_AUTH') {
        return await _createOperation.completer!.future;
      }

      _createOperation.reset();
      return result?.toString();
    } catch (e, stackTrace) {
      _createOperation.completer?.completeError(
        AndroidMediaStoreException(e.toString(), stackTrace.toString()),
      );
      rethrow;
    }
  }

  /// Copies a media file to a specific destination path or URI.
  ///
  /// Note: When copying to a URI, the destination URI must already exist in the MediaStore.
  /// Use [createMediaFile] methods for new entries.
  ///
  /// Returns the Content URI of the destination.
  Future<String?> copyMediaFileToPathOrUri(
    String toPathOrUri,
    String fromPathOrUri, {
    String? mimeType,
    Function? onComplete,
  }) async {
    try {
      await ensureInitialized();
      _createOperation.set(Completer(), onComplete);

      final result = await mediaChannel
          .invokeMethod('copyMediaFileToPathOrUri', {
            'toPathOrUri': toPathOrUri,
            'fromPathOrUri': fromPathOrUri,
            'mimeType': mimeType,
          });

      if (result == 'PENDING_AUTH') {
        return await _createOperation.completer!.future;
      }

      _createOperation.reset();
      return result?.toString();
    } catch (e, stackTrace) {
      _createOperation.completer?.completeError(
        AndroidMediaStoreException(e.toString(), stackTrace.toString()),
      );
      rethrow;
    }
  }
}
