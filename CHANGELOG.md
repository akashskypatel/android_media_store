## 0.0.3

* Added platform checks to Dart bridge methods to prevent `MissingPluginException` on non-Android platforms.
* Updated example `pubspec.yaml` SDK constraints for consistency.

## 0.0.2

* Fix `README`

## 0.0.1

* Initial release of `android_media_store`.
* Full support for Android Scoped Storage (API 29+).
* Safe `content://` URI handling with memory-efficient streaming to prevent OOM errors.
* Automatic permission management, including the `MANAGE_MEDIA` special permission.
* Implementation of a 1MB safety limit for direct memory reads to avoid `TransactionTooLargeException`.
* Support for MediaStore operations: create, read, edit, copy, and delete.
* Bi-directional conversion between file system paths and MediaStore URIs.
