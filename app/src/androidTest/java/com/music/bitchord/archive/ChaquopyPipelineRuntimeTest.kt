package com.music.bitchord.archive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChaquopyPipelineRuntimeTest {
    @Test
    fun embeddedPipelineAndNativeExtensionsImportOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        val python = Python.getInstance()

        listOf(
            "soiboi_pipeline",
            "soiboi_pipeline.__main__",
            "gamdl",
            "gamdl._ammuxer",
            "_bliss_analyze",
        ).forEach { moduleName ->
            assertNotNull("Chaquopy could not import $moduleName", python.getModule(moduleName))
        }
    }
}
