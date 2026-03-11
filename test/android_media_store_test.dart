import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:android_media_store/android_media_store.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('readMediaFile returns bytes or throws FILE_TOO_LARGE', () async {
    const channel = MethodChannel('com.akashskypatel.android_media_store');

    // Simulate Native side returning an error
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
          if (methodCall.method == 'readMediaFile') {
            throw PlatformException(code: 'FILE_TOO_LARGE', message: 'Too big');
          }
          return null;
        });

    final mediaStore = AndroidMediaStore.instance;
    // We expect the fallback logic to trigger
    expect(
      () => mediaStore.readMediaFile('some/path'),
      throwsA(isA<AndroidMediaStoreException>()),
    );
  });
}
