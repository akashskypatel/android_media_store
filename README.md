# android_media_store

[![Pub Version](https://img.shields.io/pub/v/android_media_store.svg)](https://pub.dev/packages/android_media_store)
[![GitHub stars](https://img.shields.io/github/stars/akashskypatel/android_media_store?style=social)](https://github.com/akashskypatel/android_media_store)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![GitHub issues](https://img.shields.io/github/issues/akashskypatel/android_media_store)](https://github.com/akashskypatel/android_media_store/issues)

A safe and modern Flutter plugin for accessing and managing media files on Android, addressing the challenges of Scoped Storage and `content://` URI handling. This plugin simplifies complex tasks like file creation, reading, editing, and deletion while automatically managing Android 10+ (API 29+) storage permissions.

## Key Features

*   **Scoped Storage Compatibility:** Seamlessly handles Android 10+ (API 29+) Scoped Storage restrictions, automatically requesting necessary permissions and ensuring data integrity.
*   **Safe `content://` URI Handling:** Converts `content://` URIs to file paths safely, reading files in a memory-efficient, chunked manner.
*   **Automatic Permission Management:** Automatically requests the `MANAGE_MEDIA` and other essential permissions (if needed) when required, streamlining the development process.
*   **OOM and TransactionTooLargeException Prevention:** Implements safe reading techniques to prevent Out-Of-Memory errors, and handles the Android IPC (Binder) transaction size limit.
*   **Comprehensive File Operations:** Supports creating, reading, editing, copying, and deleting media files within the MediaStore.
*   **Easy-to-Use API:** Simple, well-documented methods to perform common media file operations.
*   **Robust Example App:** Includes a fully functional example app that demonstrates all the features of the plugin.

## Getting Started

### 1.  Add the dependency

Add `android_media_store` to your project's `pubspec.yaml`:

```yaml
dependencies:
  android_media_store: ^0.0.1 # Replace with the latest version
```

Then, run `flutter pub get`.

### 2. Android Setup (AndroidManifest.xml)

Add the following permissions to your `android/app/src/main/AndroidManifest.xml` file:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
  package="YOUR_PACKAGE_NAME">  <!-- Replace with your app's package name -->

    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    <uses-permission android:name="android.permission.MANAGE_MEDIA" />
    <uses-permission android:name="android.permission.ACCESS_MEDIA_LOCATION" />

    <!-- For Android 12 and below -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
    <!-- For devices running Android 9 (API 28) and lower -->
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>

    <application
        ... >
      </application>
</manifest>
```

**Important:** The `MANAGE_MEDIA` permission is a "Special Permission." Google Play has strict policies on using this. Ensure your app's primary function is a File Manager or Media Manager; otherwise, your app may be rejected during review.

### 3. Import the Plugin

```dart
import 'package:android_media_store/android_media_store.dart';
```

### 4. Usage

Create an instance of `AndroidMediaStore`:

```dart
final mediaStore = AndroidMediaStore.instance;
```

Now, you can use the various methods:

```dart
// 1. Request Permissions (Recommended, but handled internally by the plugin)
// if (await mediaStore.canManageMedia() == false) {
//   await mediaStore.requestManageMedia();
// }

// 2. Path to URI conversion
final String? uri = await mediaStore.pathToUri(
  '/storage/emulated/0/Download/my_audio.mp3', // Replace with your path
  mimeType: 'audio/mpeg', // OPTIONAL: Improves accuracy
);

// 3. Create a file
final Uint8List data = Uint8List.fromList('Your file content'.codeUnits);
final String? createdUri = await mediaStore.createMediaFile(
  'my_document.txt',
  data,
  mimeType: 'text/plain',
);

// 4.  Read a file (Safe! Handles large files by caching)
final bytes = await mediaStore.readMediaFile(createdUri!);

// 5. Delete a file
final success = await mediaStore.deleteMediaFile(createdUri!);

// 6. Edit an existing file (e.g., a video or image)
final newData = Uint8List.fromList('New content'.codeUnits);
final editResult = await mediaStore.editMediaFile(createdUri!, newData);

// 7. Copy a file
final String? copiedUri = await mediaStore.copyMediaFileToRelative(
    createdUri!,
    'copied_file.txt',
    relativePath: 'Download/',  // target dir
    mimeType: 'text/plain',
  );
```

### 5. Example App

A complete example app is available in the `example/` directory. This app demonstrates all of the plugin's features and handles all the necessary permission requests.

## Methods

*   **`Future<String?> pathToUri(String path, {String? mimeType})`**:
    *   Converts a file system path to a MediaStore or FileProvider compatible URI.
    *   If you provide a `mimeType`, the result is more accurate.
    *   Returns the content URI as a `String`.
    *   Returns `null` if the conversion fails.

*   **`Future<String?> uriToPath(String uri)`**:
    *   Converts a MediaStore or FileProvider URI back to a file system path.
    *   Returns the file path as a `String`.
    *   Returns `null` if no path is found.

*   **`Future<String?> editMediaFile(String pathOrUri, List<int> data, {String? mimeType, void Function(String?)? onSuccess, void Function(Exception)? onFail})`**:
    *   Overwrites an existing media file with new binary data.
    *   If the app doesn't own the file, it triggers a permission dialog automatically.
    *   Uses `onSuccess` callback to be notified of success and `onFail` callback to handle failures.
    *   Returns the URI of the edited file (as a string), or calls the onFail callback.
    *   If an error occurs, the `onFail` callback is invoked with an `AndroidMediaStoreException`.

*   **`Future<String?> getReadableMediaFilePath(String pathOrUri)`**:
    *   Returns a physical, readable file path for a given media URI.
    *   If the URI is `content://`, the plugin will *stream* the content into a file in the app's cache directory to avoid OOM errors.
    *   This is the preferred method to read large files safely.
    *   Remember to delete cached files:  `File(path).delete()`.

*   **`Future<Uint8List?> readMediaFile(String pathOrUri, {String? mimeType})`**:
    *   Reads the complete binary content of a media file *directly into memory*.
    *   **Important:** This method includes a built-in size limit (1MB) to prevent `OutOfMemoryError` and `TransactionTooLargeException` crashes.
    *   If the file exceeds the 1MB limit, the plugin will throw an `AndroidMediaStoreException` with the code `FILE_TOO_LARGE`. The caller should then use `getReadableMediaFilePath()` to safely read the file.

*   **`Future<bool> deleteMediaFile(String pathOrUri, {void Function(bool)? onSuccess, void Function(Exception)? onFail})`**:
    *   Permanently deletes a media file from storage.
    *   Handles permission requests automatically.
    *   Uses `onSuccess` and `onFail` callbacks.
    *   Returns `true` if the deletion was successful (or queued).

*   **`Future<String?> createMediaFileAtRelative(String displayName, String relativePath, List<int> data, {String? mimeType, void Function(String?)? onSuccess, void Function(Exception)? onFail})`**:
    *   Creates a new media file in a specific MediaStore relative directory (e.g., `DIRECTORY_PICTURES`).
    *   Uses `onSuccess` and `onFail` callbacks.
    *   Returns the content URI of the created file.

*   **`Future<String?> createMediaFile(String displayName, List<int> data, {String? mimeType, void Function(String?)? onSuccess, void Function(Exception)? onFail})`**:
    *   Creates a new media file with automatic directory selection based on the `mimeType`.
    *   Uses `onSuccess` and `onFail` callbacks.
    *   Returns the content URI of the created file.

*   **`Future<String?> copyMediaFileToRelative(String pathOrUri, String displayName, {String? relativePath, String? mimeType, void Function(String?)? onSuccess, void Function(Exception)? onFail})`**:
    *   Copies a media file to a new location within a relative directory.
    *   Handles permissions automatically.
    *   Uses `onSuccess` and `onFail` callbacks.
    *   Returns the content URI of the new copy.

*   **`Future<String?> copyMediaFileToPathOrUri(String toPathOrUri, String fromPathOrUri, {String? mimeType, void Function(String?)? onSuccess, void Function(Exception)? onFail})`**:
    *   Copies a media file to a specific destination path or URI.
    *   Handles permissions automatically.
    *   Uses `onSuccess` and `onFail` callbacks.
    *   Returns the content URI of the new copy.

*   **`Future<bool> canManageMedia()`**:
    *   Checks whether the app currently has media management privileges.

*   **`Future<void> requestManageMedia()`**:
    *   Opens the system settings screen where the user can grant media management access.

## Troubleshooting

*   **"MEDIA\_STORE\_ERROR" or Permissions Issues:**  Double-check that you have added the necessary permissions to your `AndroidManifest.xml`. Android 11+ may require the user to explicitly grant media management permissions via the System Settings.
*   **"File is too large"**: The `readMediaFile` method has a 1MB limit. For larger files, use `getReadableMediaFilePath()` and stream the data.
*   **Test builds on real devices:**  Testing the plugin requires real devices. It is highly recommended that you test on Android 10+ (API 29+) and Android 12+ (API 31+).
*   **`android.os.TransactionTooLargeException`**: This is a classic Binder limit error. Use `getReadableMediaFilePath()` to avoid this for files larger than 1MB.
*   **Permissions**: Older android API may need [permission_handler](https://pub.dev/packages/permission_handler) plugin for more specific permissions
## Contributions

Contributions are welcome! Please see the [contributing guidelines](CONTRIBUTING.md) for details.

## License

This project is licensed under the [MIT License](LICENSE).

---

This README provides a solid foundation for your plugin. Remember to:

*   **Update it regularly:** Keep the documentation in sync with your code changes.
*   **Link to your contributing guidelines:** If you're actively developing, this makes it easier for others to submit pull requests.
*   **Add a CHANGELOG.md file:** This helps users understand the history of the plugin and what has changed in each version.
*   **Test thoroughly:** Make sure the example app is working correctly on multiple Android versions.