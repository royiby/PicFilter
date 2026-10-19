package com.picfilter.spike.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which folders onboarding samples. Getting this wrong is quiet: the scan still
 * runs, it just learns the wrong faces, so the shapes WhatsApp has actually
 * used on real phones are pinned here.
 */
class WhatsAppFolderTest {

    private fun wa(path: String?, bucket: String? = null) =
        Clusterer.isWhatsAppFolder(path, bucket)

    @Test
    fun `the scoped-storage media folder counts`() {
        assertTrue(wa("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "WhatsApp Images"))
    }

    @Test
    fun `so does the folder a restore leaves in Pictures`() {
        // This is the one the spike's "%com.whatsapp%" filter missed: 638 images
        // on the test phone sat in Pictures/Whatsapp with no package in the path.
        assertTrue(wa("Pictures/Whatsapp/", "Whatsapp"))
    }

    @Test
    fun `and the old top-level tree`() {
        assertTrue(wa("WhatsApp/Media/WhatsApp Images/", "WhatsApp Images"))
    }

    @Test
    fun `the bucket alone is enough when the path is missing`() {
        assertTrue(wa(null, "WhatsApp Images"))
        assertTrue(wa("", "WhatsApp Video"))
    }

    @Test
    fun `case does not matter`() {
        assertTrue(wa("pictures/whatsapp/", null))
        assertTrue(wa("Pictures/WHATSAPP/", null))
    }

    @Test
    fun `everything else is left out`() {
        assertFalse(wa("DCIM/Camera/", "Camera"))
        assertFalse(wa("Pictures/Screenshots/", "Screenshots"))
        assertFalse(wa("Pictures/Telegram/", "Telegram"))
        assertFalse(wa("Pictures/PicFilter/", "PicFilter"))
        assertFalse(wa(null, null))
    }
}
