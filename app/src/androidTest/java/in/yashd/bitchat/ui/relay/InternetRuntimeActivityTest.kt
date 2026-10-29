package `in`.yashd.bitchat.ui.relay

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import `in`.yashd.bitchat.MainActivity
import `in`.yashd.bitchat.RelayApplication
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class InternetRuntimeActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun recreationKeepsApplicationRuntimeAndForegroundReceptionStopsWhenHidden() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("notification_permission", Context.MODE_PRIVATE)
            .edit().putBoolean("requested", true).commit()
        val runtime = (context.applicationContext as RelayApplication).internetRuntime
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(10_000) { runtime.state.value.active && !runtime.state.value.storageLoading }
            val identity = runtime.state.value.publicKey
            val before = runtime.state
            scenario.recreate()
            scenario.onActivity {
                assertSame(runtime, (it.application as RelayApplication).internetRuntime)
                assertSame(before, runtime.state)
                assertTrue(runtime.state.value.active)
                assertEquals(identity, runtime.state.value.publicKey)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            // Process lifecycle deliberately delays stop to bridge Activity recreation.
            compose.waitUntil(10_000) { !runtime.state.value.active }
            assertTrue(runtime.state.value.connections.isEmpty())
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { runtime.state.value.active }
            assertEquals(identity, runtime.state.value.publicKey)
        }
    }
}
