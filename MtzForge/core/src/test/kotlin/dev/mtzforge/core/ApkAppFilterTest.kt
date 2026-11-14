package dev.mtzforge.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApkAppFilterTest {
    private val apk = File("../dist/ForgeSampleIcons-debug.apk")

    @Test fun readsBinaryAppFilterFromRealApk() {
        val found = assertNotNull(ApkAppFilter.find(apk))
        assertEquals("res/xml/appfilter.xml", found.entry)
        val text = AppFilter.parseXml(File("../sample-iconpack/src/main/res/xml/appfilter.xml").readBytes())
        assertEquals(text.items, found.filter.items, "el AXML decodificado coincide con el XML fuente")
        assertEquals(text.iconMask, found.filter.iconMask)
        assertEquals(0.85f, found.filter.scale)
    }

    @Test fun extractsBaseApkFromBundle() {
        val bundle = File.createTempFile("bundle", ".apks").apply { deleteOnExit() }
        ZipOutputStream(bundle.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("config.xxhdpi.apk")); z.write(ByteArray(10)); z.closeEntry()
            z.putNextEntry(ZipEntry("base.apk")); apk.inputStream().use { it.copyTo(z) }; z.closeEntry()
        }
        assertEquals(2, ApkAppFilter.innerApks(bundle).size)
        val base = ApkAppFilter.extractBaseApk(bundle, File.createTempFile("base", ".apk").apply { deleteOnExit() })
        assertNotNull(ApkAppFilter.find(base))
        assertTrue(ApkAppFilter.isZip(base))
        assertTrue(!ApkAppFilter.isZip(File("src/test/resources/fonts/ForgeTestSans-Regular.ttf")))
    }
}
