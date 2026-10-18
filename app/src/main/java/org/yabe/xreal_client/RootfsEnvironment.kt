package org.yabe.xreal_client

import android.content.res.AssetManager
import com.andlify.library.ChrootNative
import java.io.File

internal class RootfsEnvironment(
    private val filesDirectory: File,
    private val assets: AssetManager,
    private val log: (String) -> Unit,
) {
    companion object {
        private const val IMAGE_NAME = "image.tar.zst"
    }

    val path: String = File(filesDirectory, "rootfs").absolutePath

    @Synchronized
    fun ensureExtracted(): Boolean {
        if (ChrootNative.is_rootfs_extracted(path)) {
            return true
        }

        log("Rootfs is not extracted, extracting...")
        val imageFile = File(filesDirectory, IMAGE_NAME)
        assets.open(IMAGE_NAME).use { input ->
            imageFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val extracted = ChrootNative.extract_rootfs(imageFile.absolutePath, path)
        log(if (extracted) "Rootfs extracted successfully" else "Failed to extract rootfs")
        return extracted
    }

    val configFile = File(path, "etc/yabe/config.lua")

    fun readConfig(): String = configFile.readText()
}
