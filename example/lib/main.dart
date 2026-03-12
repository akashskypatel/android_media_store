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
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:android_media_store/android_media_store.dart';

void main() => runApp(const MaterialApp(
      home: MediaStoreExample(),
      debugShowCheckedModeBanner: false,
    ));

class MediaStoreExample extends StatefulWidget {
  const MediaStoreExample({super.key});

  @override
  State<MediaStoreExample> createState() => _MediaStoreExampleState();
}

class _MediaStoreExampleState extends State<MediaStoreExample> {
  final _mediaStore = AndroidMediaStore.instance;
  late StreamSubscription<bool> _permissionStreamSub;

  String _status = 'Ready';
  String? _targetUri; // Single source of truth for the currently active file

  @override
  void initState() {
    super.initState();
    _initializePlugin();

    // IMPROVEMENT: React automatically to manage media permission changes
    _permissionStreamSub = _mediaStore.onManageMediaPermissionChanged.listen((isGranted) {
      if (mounted) {
        setState(() {
          _status = isGranted 
            ? 'Manage Media Permission: Granted' 
            : 'Manage Media Permission: Denied';
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(_status)),
        );
      }
    });
  }

  @override
  void dispose() {
    _permissionStreamSub.cancel();
    super.dispose();
  }

  Future<void> _initializePlugin() async {
    try {
      await AndroidMediaStore.ensureInitialized();
      setState(() => _status = 'Plugin initialized successfully');
      await _checkPermissions(silent: true);
    } catch (e) {
      setState(() => _status = 'Initialization failed: $e');
    }
  }

  // ------------------------------------------------------------------
  // PERMISSION HELPERS
  // ------------------------------------------------------------------

  Future<void> _checkPermissions({bool silent = false}) async {
    if (!silent) setState(() => _status = 'Checking permissions...');
    try {
      // 1. Check Standard Storage / Media Permissions (permission_handler)
      await [
        Permission.photos,
        Permission.audio,
        Permission.videos,
        Permission.storage,
      ].request();

      // 2. Check Android 12+ Manage Media Access (Native Plugin)
      bool canManageMedia = await _mediaStore.canManageMedia();

      if (!canManageMedia) {
        setState(() => _status = 'Missing Manage Media Permission');
        await _mediaStore.requestManageMedia(); // Will trigger the stream when user returns
      } else {
        setState(() => _status = 'All permissions look good!');
      }
    } catch (e) {
      setState(() => _status = 'Permission check error: $e');
    }
  }

  // ------------------------------------------------------------------
  // PLUGIN DEMO OPERATIONS
  // ------------------------------------------------------------------

  // 1. Get Platform Version
  Future<void> _getPlatformVersion() async {
    setState(() => _status = 'Getting platform version...');
    try {
      final version = await _mediaStore.getPlatformVersion();
      setState(() => _status = 'Platform version: $version');
    } catch (e) {
      setState(() => _status = 'Error: $e');
    }
  }

  // 2. Create a file (automatic directory based on MIME)
  Future<void> _createFile() async {
    setState(() => _status = 'Creating file...');
    try {
      final data = Uint8List.fromList('Hello from Android Media Store!'.codeUnits);
      final uri = await _mediaStore.createMediaFile(
        'demo_text.txt',
        data,
        mimeType: 'text/plain',
      );
      setState(() {
        _targetUri = uri;
        _status = 'Created: $uri';
      });
    } catch (e) {
      setState(() => _status = 'Create error: $e');
    }
  }

  // 3. Create file at specific relative path
  Future<void> _createFileAtRelative() async {
    setState(() => _status = 'Creating file at Documents...');
    try {
      final data = Uint8List.fromList('Document content'.codeUnits);
      // Thanks to the Kotlin fix, we can safely use slashes here now!
      final uri = await _mediaStore.createMediaFileAtRelative(
        'demo_document.txt',
        'Documents/', 
        data,
        mimeType: 'text/plain',
      );
      setState(() {
        _targetUri = uri;
        _status = 'Created at Documents: $uri';
      });
    } catch (e) {
      setState(() => _status = 'Create at relative error: $e');
    }
  }

  // 4. Read file
  Future<void> _readFile() async {
    if (_targetUri == null) {
      setState(() => _status = 'No file to read. Create one first.');
      return;
    }
    setState(() => _status = 'Reading file...');
    try {
      final bytes = await _mediaStore.readMediaFile(_targetUri!);
      final content = String.fromCharCodes(bytes!);
      setState(() => _status = 'Read (${bytes.length} bytes): $content');
    } catch (e) {
      setState(() => _status = 'Read error: $e');
    }
  }

  // 5. Get readable file path (creates cache copy)
  Future<void> _getReadablePath() async {
    if (_targetUri == null) {
      setState(() => _status = 'No file to get path for. Create one first.');
      return;
    }
    setState(() => _status = 'Getting readable path...');
    try {
      final path = await _mediaStore.getReadableMediaFilePath(_targetUri!);
      setState(() => _status = 'Readable path: $path');
    } catch (e) {
      setState(() => _status = 'Get readable path error: $e');
    }
  }

  // 6. Edit file
  Future<void> _editFile() async {
    if (_targetUri == null) {
      setState(() => _status = 'No file to edit. Create one first.');
      return;
    }
    setState(() => _status = 'Editing file...');
    try {
      final newData = Uint8List.fromList('Updated content at ${DateTime.now()}!'.codeUnits);
      final result = await _mediaStore.editMediaFile(_targetUri!, newData);
      setState(() => _status = 'Edit result: $result');
    } catch (e) {
      setState(() => _status = 'Edit error: $e');
    }
  }

  // 7. Copy file to relative path
  Future<void> _copyToRelative() async {
    if (_targetUri == null) {
      setState(() => _status = 'No file to copy. Create one first.');
      return;
    }
    setState(() => _status = 'Copying to Downloads...');
    try {
      final uri = await _mediaStore.copyMediaFileToRelative(
        _targetUri!,
        'copied_demo_${DateTime.now().millisecondsSinceEpoch}.txt',
        relativePath: 'Download/',
        mimeType: 'text/plain',
      );
      setState(() {
        _targetUri = uri; // Track the new copy
        _status = 'Copied to Downloads: $uri';
      });
    } catch (e) {
      setState(() => _status = 'Copy to relative error: $e');
    }
  }

  // 8. Convert URI to Path, then test Path to URI (Fix for hardcoded paths)
  Future<void> _testPathConversions() async {
    if (_targetUri == null) {
      setState(() => _status = 'No URI to convert. Create a file first.');
      return;
    }
    setState(() => _status = 'Converting...');
    try {
      // Step 1: URI -> Path
      final path = await _mediaStore.uriToPath(_targetUri!);
      if (path == null) throw Exception("Could not resolve path from URI");
      
      // Step 2: Path -> URI
      final uri = await _mediaStore.pathToUri(path, mimeType: 'text/plain');
      setState(() => _status = 'URI -> Path: $path\n\nPath -> URI: $uri');
    } catch (e) {
      setState(() => _status = 'Conversion error: $e');
    }
  }

  // 9. Delete file
  Future<void> _deleteFile() async {
    if (_targetUri == null) {
      setState(() => _status = 'No file to delete. Create or copy one first.');
      return;
    }
    setState(() => _status = 'Deleting file...');
    try {
      final success = await _mediaStore.deleteMediaFile(_targetUri!);
      if (success) {
        setState(() {
          _targetUri = null;
          _status = 'Delete success: $success';
        });
      } else {
        setState(() => _status = 'Failed to delete file.');
      }
    } catch (e) {
      setState(() => _status = 'Delete error: $e');
    }
  }

  // ------------------------------------------------------------------
  // UI BUILDER
  // ------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Android Media Store Plugin'),
        backgroundColor: Theme.of(context).colorScheme.primaryContainer,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children:[
            // Status Display
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(8),
              ),
              child: SelectableText(
                'Status:\n$_status\n\nActive Target URI:\n${_targetUri ?? "None"}',
                style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
              ),
            ),
            const SizedBox(height: 20),

            _buildSection('Initialization & Permissions',[
              ElevatedButton.icon(
                onPressed: _getPlatformVersion,
                icon: const Icon(Icons.info),
                label: const Text('Platform Version'),
              ),
              ElevatedButton.icon(
                onPressed: () => _checkPermissions(silent: false),
                icon: const Icon(Icons.verified_user),
                label: const Text('Check / Request Permissions'),
              ),
            ]),

            _buildSection('File Creation',[
              ElevatedButton.icon(
                onPressed: _createFile,
                icon: const Icon(Icons.add),
                label: const Text('Create (Auto Directory)'),
              ),
              ElevatedButton.icon(
                onPressed: _createFileAtRelative,
                icon: const Icon(Icons.folder),
                label: const Text('Create in Documents/'),
              ),
            ]),

            _buildSection('Operations (Acts on Active URI)',[
              ElevatedButton.icon(
                onPressed: _readFile,
                icon: const Icon(Icons.read_more),
                label: const Text('Read File Bytes'),
              ),
              ElevatedButton.icon(
                onPressed: _getReadablePath,
                icon: const Icon(Icons.file_present),
                label: const Text('Get Cache Path (Large Files)'),
              ),
              ElevatedButton.icon(
                onPressed: _editFile,
                icon: const Icon(Icons.edit),
                label: const Text('Edit File Content'),
              ),
              ElevatedButton.icon(
                onPressed: _copyToRelative,
                icon: const Icon(Icons.copy),
                label: const Text('Copy to Downloads/'),
              ),
              ElevatedButton.icon(
                onPressed: _testPathConversions,
                icon: const Icon(Icons.swap_horiz),
                label: const Text('Test Path ↔ URI Conversion'),
              ),
              ElevatedButton.icon(
                onPressed: _deleteFile,
                icon: const Icon(Icons.delete, color: Colors.red),
                label: const Text('Delete File'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.red.shade100,
                  foregroundColor: Colors.red.shade900,
                ),
              ),
            ]),

            const SizedBox(height: 20),
            const Divider(),
            const Padding(
              padding: EdgeInsets.all(8.0),
              child: Text(
                '💡 Tip: Create a file first, then use the Operations section to interact with it.',
                style: TextStyle(fontStyle: FontStyle.italic, color: Colors.grey),
              ),
            )
          ],
        ),
      ),
    );
  }

  Widget _buildSection(String title, List<Widget> buttons) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children:[
        Text(
          title,
          style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 8),
        Wrap(spacing: 8, runSpacing: 8, children: buttons),
        const SizedBox(height: 16),
      ],
    );
  }
}