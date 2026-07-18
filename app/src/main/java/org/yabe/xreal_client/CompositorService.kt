package org.yabe.xreal_client

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import org.yabe.xreal.ICompositorService
import org.yabe.xreal_client.ui.theme.YabeXREALClientTheme
import com.andlify.library.ChrootNative
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AndlifyEntrypointRunnable : Runnable {
    override fun run() {
        andlifyEntrypoint()
    }

    private external fun andlifyEntrypoint()
}


class CompositorService : Service() {
    interface ILogger {
        fun appendLog(message: String)
    }

    companion object {
        var listener: ILogger? = null

        init {
            System.loadLibrary("xreal_client")
        }
    }

    fun log(message: String) {
        Log.d("YabeXrealClient", message)
        listener?.appendLog(message)
    }

    private var compositorService: ICompositorService? = null
    private var isBoundToCompositorService = false
    private var isClientStarted = false
    private val mBinder = object : ICompositorService.Stub() {
        override fun isCompositorReady(): Boolean {
            log("Unexpected call from compositor: isCompositorReady()")
            return false
        }

        override fun notifyCompositorReady() {
            log("Compositor started")
            connectToRunningCompositor()
        }

        override fun addClientFd(pfd: ParcelFileDescriptor?) {
            log("Unexpected call from compositor: addClient()")
        }
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            log("CompositorService connected")
            compositorService = ICompositorService.Stub.asInterface(service)
            try {
                if (compositorService?.isCompositorReady() == true) {
                    log("Compositor is already ready")
                    startClientIfNeeded()
                }
            } catch (e: Exception) {
                log("Failed to check compositor readiness: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            log("CompositorService disconnected")
            unbindFromCompositorService()
        }
    }

    private fun connectToRunningCompositor() {
        if (compositorService != null) {
            startClientIfNeeded()
            return
        }
        if (isBoundToCompositorService) {
            return
        }

        val intent = Intent()
        intent.component = ComponentName("org.yabe.xreal", "org.yabe.xreal.CompositorService")
        val success = bindService(intent, serviceConnection, 0)
        isBoundToCompositorService = success
        if (success) {
            log("bindService() succeeded")
        } else {
            log("bindService() failed")
        }
    }

    private fun unbindFromCompositorService() {
        compositorService = null
        if (!isBoundToCompositorService) {
            return
        }

        try {
            unbindService(serviceConnection)
        } finally {
            isBoundToCompositorService = false
        }
    }

    private val rootfsPath: String
        get() = File(filesDir, "rootfs").absolutePath

    private fun ensureRootfsExtracted() {
        if (ChrootNative.is_rootfs_extracted(rootfsPath)) {
            return
        }
        log("Rootfs is not extracted, extracting...")
        val imageFileName = "image.tar.zst"
        val imageFile = File(filesDir, imageFileName)
        assets.open(imageFileName).use { inputStream ->
            imageFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        val result = ChrootNative.extract_rootfs(imageFile.absolutePath, rootfsPath)
        if (result) {
            log("Rootfs extracted successfully")
        } else {
            log("Failed to extract rootfs")
        }
    }

    private val executor: ExecutorService = Executors.newCachedThreadPool()
    var stderrFd: Array<ParcelFileDescriptor>? = null
    private val stderrBuilder = StringBuilder()

    private fun saveAndLogStderr() {
        val stderrContent = stderrBuilder.toString()
        try {
            File(filesDir, "stderr.txt").writeText(stderrContent)
        } catch (e: Exception) {
            log("Failed to save stderr to file: ${e.message}")
        }
    }


    private fun startClient() {
        try {
            File(filesDir, "stderr.txt").writeText("")
        } catch (e: Exception) {
            log("Failed to init stderr file: ${e.message}")
        }
        ensureRootfsExtracted()
        val fd = createSocketFd("$rootfsPath/tmp")
        if (fd < 0) {
            log("Failed to create socket fd")
            return
        }

        val stdinFd = ParcelFileDescriptor.createPipe()
        val stdoutFd = ParcelFileDescriptor.createPipe()
        stderrFd = ParcelFileDescriptor.createPipe()
        executor.execute {
            val stdoutReader = ParcelFileDescriptor.AutoCloseInputStream(stdoutFd[0])
            val buffer = ByteArray(1024)
            while (true) {
                val stdoutBytesRead = stdoutReader.read(buffer)
                if (stdoutBytesRead > 0) {
                    val stdoutMessage = String(buffer, 0, stdoutBytesRead)
                    log(stdoutMessage)
                } else {
                    break
                }
            }
        }
        executor.execute {
            val stderrReader = ParcelFileDescriptor.AutoCloseInputStream(stderrFd!![0])
            val buffer = ByteArray(1024)
            while (true) {
                val stderrBytesRead = stderrReader.read(buffer)
                if (stderrBytesRead > 0) {
                    val stderrMessage = String(buffer, 0, stderrBytesRead)
                    log(stderrMessage)
                } else {
                    saveAndLogStderr()
                    break
                }
            }
        }
        log("Chroot starting...")
        val newPid = ChrootNative.start_chroot_func(
            rootfsPath,
            AndlifyEntrypointRunnable(),
            stdinFd[1].fd,
            stdoutFd[1].fd,
            stderrFd!![1].fd
        )
        if (newPid > 0) {
            log("Chroot started successfully")
        }

        executor.execute {
            while (true) {
                val acceptedFd = acceptSocket()
                if (acceptedFd < 0) {
                    log("Failed to accept socket")
                    return@execute
                }
                sendClientFd(acceptedFd)
            }
        }
    }

    private fun startClientIfNeeded() {
        if (isClientStarted) {
            log("Client is already started")
            return
        }
        isClientStarted = true
        startClient()
    }

    private fun sendClientFd(acceptedFd: Int) {
        val service = compositorService
        val pfd = ParcelFileDescriptor.fromFd(acceptedFd)
        if (service == null) {
            log("CompositorService is not connected; dropping fd: $acceptedFd")
            pfd.close()
            return
        }
        try {
            pfd.use {
                service.addClientFd(it)
            }
            log("Sent client fd to compositor: $acceptedFd")
        } catch (e: Exception) {
            log("Failed to send client fd to compositor: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        log("CompositorService onCreate")
        connectToRunningCompositor()
    }

    override fun onBind(intent: Intent?): IBinder {
        log("CompositorService bound by intent: $intent")
        return mBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        log("CompositorService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        log("CompositorService onDestroy")
        saveAndLogStderr()
        unbindFromCompositorService()
        executor.shutdownNow()
        super.onDestroy()
    }

    private external fun createSocketFd(socketDir: String): Int
    private external fun acceptSocket(): Int
}
