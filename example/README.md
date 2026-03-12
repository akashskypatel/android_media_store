# Android Media Store Example

A comprehensive example application demonstrating the features of the `android_media_store` plugin.

## Overview

This example app provides a hands-on way to test Android Scoped Storage operations. It covers:

1.  **Permission Management**: Requesting standard media permissions and the special "Manage Media" permission (Android 12+).
2.  **File Creation**: Creating files in both automatically determined directories (based on MIME type) and specific subdirectories (e.g., `Documents/MyFolder`).
3.  **Media Operations**: Reading, editing, copying, and deleting files using `content://` URIs.
4.  **Large File Handling**: Demonstrating the safety fallback mechanism for files larger than 1MB to avoid `TransactionTooLargeException`.
5.  **Path Conversions**: Converting between physical file system paths and MediaStore URIs.

## Getting Started

### Prerequisites

*   **Real Android Device**: Scoped Storage behavior varies significantly between API levels and is best tested on a physical device.
*   **Android API Level**:
    *   API 29 (Android 10) or higher is recommended for full Scoped Storage testing.
    *   API 31 (Android 12) or higher is required for the `MANAGE_MEDIA` permission features.

### Running the Example

1.  Clone the repository.
2.  Navigate to the `example` directory:
    ```bash
    cd example
    ```
3.  Install dependencies:
    ```bash
    flutter pub get
    ```
4.  Run the app:
    ```bash
    flutter run
    ```

## Usage Tips

*   **Initialize First**: Tap "Platform Version" to ensure the plugin and MethodChannel are correctly set up.
*   **Grant Permissions**: Use the "Check / Request Permissions" button. Note that "Manage Media" will take you to system settings.
*   **The "Active Target URI"**: Most operations (Read, Edit, Copy, Delete) act on the file most recently created. Look at the status box to see the current active URI.
*   **Observe System Dialogs**: When deleting or editing a file not owned by the app (and if "Manage Media" is not granted), observe how the Android system automatically prompts for confirmation.
*   **Check Logs**: The app prints status updates to the console, including details about URI to Path conversions and permission state changes.
