// This is a basic Flutter integration test.
//
// Since integration tests run in a full Flutter application, they can interact
// with the host side of a plugin implementation, unlike Dart unit tests.
//
// For more information about Flutter integration tests, please see
// https://flutter.dev/to/integration-testing

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'package:android_media_store/android_media_store.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('verify file creation flows', (WidgetTester tester) async {
    final mediaStore = AndroidMediaStore.instance;

    final List<int> testData = [1, 2, 3, 4];
    final uri = await mediaStore.createMediaFile('test.txt', testData);

    expect(uri, isNotNull);
    expect(uri!.startsWith('content://'), isTrue);
  });
}
