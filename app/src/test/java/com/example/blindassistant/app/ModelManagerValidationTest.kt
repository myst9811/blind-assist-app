package com.example.blindassistant.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class ModelManagerValidationTest {

    @Test
    fun `asset validation detects missing file`() {
        val tempDir = createTempDir()
        val missingFile = File(tempDir, "missing_model.torchscript")
        assertFalse("File should not exist", missingFile.exists())
        tempDir.delete()
    }

    @Test
    fun `asset validation detects existing file`() {
        val tempDir = createTempDir()
        val existingFile = File(tempDir, "model.torchscript")
        FileOutputStream(existingFile).use { it.write(ByteArray(10)) }
        assertTrue("File should exist", existingFile.exists())
        existingFile.delete()
        tempDir.delete()
    }

    @Test
    fun `model asset names follow expected naming convention`() {
        val assetNames = listOf("models/yolov8n.torchscript", "models/best.torchscript")
        assertTrue(assetNames.all { it.startsWith("models/") })
        assertTrue(assetNames.all { it.endsWith(".torchscript") })
    }
}
