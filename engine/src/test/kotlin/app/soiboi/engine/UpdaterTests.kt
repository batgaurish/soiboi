package app.soiboi.engine

import app.soiboi.engine.update.isNewerVersion
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdaterTests {
    @Test fun comparesNumerically() {
        assertTrue(isNewerVersion("2.2.1", "2.2.0"))
        assertTrue(isNewerVersion("v2.10.0", "2.9.9"))
        assertTrue(isNewerVersion("3.0", "2.99.99"))
        assertFalse(isNewerVersion("2.2.0", "2.2.0"))
        assertFalse(isNewerVersion("2.1.9", "2.2.0"))
        assertFalse(isNewerVersion("2.2", "2.2.0"))
    }
}

class UnpackTests {
    @Test fun swapsTheAppFolderAndKeepsTheOldOneOnFailure() {
        val root = kotlin.io.path.createTempDirectory("upd").toFile()
        val src = java.io.File(root, "src/soiboi/bin").apply { mkdirs() }
        java.io.File(src, "soiboi").writeText("new")
        val tarball = java.io.File(root, "u.tar.gz")
        ProcessBuilder("tar", "-czf", tarball.path, "-C", java.io.File(root, "src").path, "soiboi").start().waitFor()
        val install = java.io.File(root, "install"); java.io.File(install, "app/bin").mkdirs(); java.io.File(install, "app/bin/soiboi").writeText("old")
        val u = app.soiboi.engine.update.Updater("x/y", "1.0.0", false, root)
        assertTrue(u.unpack(tarball, install) == null)
        assertTrue(java.io.File(install, "app/bin/soiboi").readText() == "new")
        assertFalse(java.io.File(install, "app.old").exists())
        val bad = java.io.File(root, "bad.tar.gz").apply { writeText("not a tar") }
        assertTrue(u.unpack(bad, install) != null)
        assertTrue(java.io.File(install, "app/bin/soiboi").readText() == "new")
        root.deleteRecursively()
    }
}
