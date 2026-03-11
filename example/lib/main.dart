import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:android_media_store/android_media_store.dart';

void main() => runApp(const MaterialApp(home: MediaStoreExample()));

class MediaStoreExample extends StatefulWidget {
  const MediaStoreExample({super.key});

  @override
  State<MediaStoreExample> createState() => _MediaStoreExampleState();
}

class _MediaStoreExampleState extends State<MediaStoreExample> {
  final _mediaStore = AndroidMediaStore.instance;
  String _status = 'Ready';

  // 1. Create a file
  Future<void> _createFile() async {
    setState(() => _status = 'Creating...');
    final data = Uint8List.fromList('Hello Reverbio!'.codeUnits);
    final uri = await _mediaStore.createMediaFile('test_file.txt', data);
    setState(() => _status = 'Created: $uri');
  }

  // 2. Read file (Demonstrates fallback)
  Future<void> _readFile(String uri) async {
    setState(() => _status = 'Reading...');
    try {
      final bytes = await _mediaStore.readMediaFile(uri);
      setState(
        () => _status =
            'Read ${bytes?.length} bytes: ${String.fromCharCodes(bytes!)}',
      );
    } catch (e) {
      setState(() => _status = 'Read Error: $e');
    }
  }

  // 3. Edit file
  Future<void> _editFile(String uri) async {
    setState(() => _status = 'Editing...');
    final data = Uint8List.fromList('Updated content!'.codeUnits);
    final result = await _mediaStore.editMediaFile(uri, data);
    setState(() => _status = 'Edit result: $result');
  }

  // 4. Delete file
  Future<void> _deleteFile(String uri) async {
    setState(() => _status = 'Deleting...');
    final success = await _mediaStore.deleteMediaFile(uri);
    setState(() => _status = 'Delete success: $success');
  }

  // 5. Path to URI conversion
  Future<void> _convertPath(String path) async {
    final uri = await _mediaStore.pathToUri(path);
    setState(() => _status = 'Converted to URI: $uri');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Media Store Plugin Example')),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          children: [
            Text(
              'Status: $_status',
              style: const TextStyle(fontWeight: FontWeight.bold),
            ),
            const Divider(),
            Wrap(
              spacing: 10,
              children: [
                ElevatedButton(
                  onPressed: _createFile,
                  child: const Text('Create'),
                ),
                ElevatedButton(
                  onPressed: () =>
                      _convertPath('/storage/emulated/0/Download/test.txt'),
                  child: const Text('Path -> URI'),
                ),
              ],
            ),
            const SizedBox(height: 20),
            const Text('Operations (Require URI from Create):'),
            // Note: In a real app, store the created URI in a variable
            ElevatedButton(
              onPressed: () => _status.contains('Created: ')
                  ? _readFile(_status.split('Created: ').last)
                  : null,
              child: const Text('Read Last Created'),
            ),
            ElevatedButton(
              onPressed: () => _status.contains('Created: ')
                  ? _editFile(_status.split('Created: ').last)
                  : null,
              child: const Text('Edit Last Created'),
            ),
            ElevatedButton(
              onPressed: () => _status.contains('Created: ')
                  ? _deleteFile(_status.split('Created: ').last)
                  : null,
              child: const Text('Delete Last Created'),
            ),
          ],
        ),
      ),
    );
  }
}
