package com.akashskypatel.android_media_store

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.mockito.Mockito
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
class AndroidMediaStoreTest {
    private lateinit var context: Context
    private lateinit var mediaStore: AndroidMediaStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Mock activity or use a dummy for testing purposes
        mediaStore = AndroidMediaStore(Mockito.mock(Activity::class.java))
    }

    @Test
    fun `test resolveUriFromString handles content prefix`() {
        val uri = "content://media/external/images/media/1"
        val resolved = mediaStore.pathToUri(context, uri)
        assertNotNull(resolved)
        assertEquals("content", resolved?.scheme)
    }
}
