package org.yabe.xreal_client

import android.app.Service
import android.content.ComponentName
import android.content.Context
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

    private var compositorService: ICompositorService? = null
    private var isBoundToCompositorService = false
    private var isClientStarted = false
    private val mBinder = object : ICompositorService.Stub() {
        override fun isCompositorReady(): Boolean {
            return isClientStarted
        }

        override fun notifyCompositorReady() {
            listener?.appendLog("Compositor started")
            startClientIfNeeded()
        }

        override fun addClientFd(pfd: ParcelFileDescriptor?) {
            listener?.appendLog("Unexpected call from compositor: addClient()")
        }
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            listener?.appendLog("CompositorService connected")
            compositorService = ICompositorService.Stub.asInterface(service)
            try {
                if (compositorService?.isCompositorReady() == true) {
                    listener?.appendLog("Compositor is already ready")
                    startClientIfNeeded()
                }
            } catch (e: Exception) {
                listener?.appendLog("Failed to check compositor readiness: ${e.message}")
                Log.e("MainActivity", "Failed to check compositor readiness", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            listener?.appendLog("CompositorService disconnected")
            compositorService = null
            isBoundToCompositorService = false
        }
    }

    private fun bindToCompositorService() {
        val intent = Intent()
        intent.component = ComponentName("org.yabe.xreal", "org.yabe.xreal.CompositorService")
        val success = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        isBoundToCompositorService = success
        if (success) {
            listener?.appendLog("bindService() succeeded")
        } else {
            listener?.appendLog("bindService() failed")
        }
    }

    private val rootfsPath: String
        get() = File(filesDir, "rootfs").absolutePath

    private fun ensureRootfsExtracted() {
        if (ChrootNative.is_rootfs_extracted(rootfsPath)) {
            return
        }
        listener?.appendLog("Rootfs is not extracted, extracting...")
        val imageFileName = "image.tar.zst"
        val imageFile = File(filesDir, imageFileName)
        assets.open(imageFileName).use { inputStream ->
            imageFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        val result = ChrootNative.extract_rootfs(imageFile.absolutePath, rootfsPath)
        if (result) {
            listener?.appendLog("Rootfs extracted successfully")
        } else {
            listener?.appendLog("Failed to extract rootfs")
        }
    }

    private val executor: ExecutorService = Executors.newCachedThreadPool()
    var stderrFd: Array<ParcelFileDescriptor>? = null
    private val stderrBuilder = StringBuilder()

    private fun saveAndLogStderr() {
        val stderrContent = synchronized(stderrBuilder) {
            stderrBuilder.toString()
        }
        Log.d("MainActivity", stderrContent)
        try {
            File(filesDir, "stderr.txt").writeText(stderrContent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to save stderr to file", e)
        }
    }


    private fun startClient() {
        synchronized(stderrBuilder) {
            stderrBuilder.clear()
        }
        try {
            File(filesDir, "stderr.txt").writeText("")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to init stderr file", e)
        }
        ensureRootfsExtracted()
        val fd = createSocketFd("$rootfsPath/tmp")
        if (fd < 0) {
            listener?.appendLog("Failed to create socket fd")
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
                    listener?.appendLog("stdout: $stdoutMessage")
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
                    synchronized(stderrBuilder) {
                        stderrBuilder.append(stderrMessage)
                    }
                    Log.d("MainActivity", stderrMessage)
                    try {
                        File(filesDir, "stderr.txt").appendText(stderrMessage)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to append stderr to file", e)
                    }
                    listener?.appendLog("stderr: $stderrMessage")
                } else {
                    saveAndLogStderr()
                    break
                }
            }
        }
        listener?.appendLog("Chroot starting...")
        val newPid = ChrootNative.start_chroot_func(
            rootfsPath,
            AndlifyEntrypointRunnable(),
            stdinFd[1].fd,
            stdoutFd[1].fd,
            stderrFd!![1].fd
        )
        if (newPid > 0) {
            listener?.appendLog("Chroot started successfully")
        }

        executor.execute {
            while (true) {
                val acceptedFd = acceptSocket()
                if (acceptedFd < 0) {
                    listener?.appendLog("Failed to accept socket")
                    return@execute
                }
                sendClientFd(acceptedFd)
            }
        }
    }

    private fun startClientIfNeeded() {
        if (isClientStarted) {
            listener?.appendLog("Client is already started")
            return
        }
        isClientStarted = true
        startClient()
    }

    private fun sendClientFd(acceptedFd: Int) {
        val service = compositorService
        val pfd = ParcelFileDescriptor.fromFd(acceptedFd)
        if (service == null) {
            listener?.appendLog("CompositorService is not connected; dropping fd: $acceptedFd")
            pfd.close()
            return
        }
        try {
            pfd.use {
                service.addClientFd(it)
            }
            listener?.appendLog("Sent client fd to compositor: $acceptedFd")
        } catch (e: Exception) {
            listener?.appendLog("Failed to send client fd to compositor: ${e.message}")
            Log.e("MainActivity", "Failed to send client fd to compositor", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        listener?.appendLog("CompositorService onCreate")
        bindToCompositorService()
    }

    override fun onBind(intent: Intent?): IBinder {
        listener?.appendLog("CompositorService bound by intent: $intent")
        return mBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        listener?.appendLog("CompositorService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        listener?.appendLog("CompositorService onDestroy")
        saveAndLogStderr()
        if (isBoundToCompositorService) {
            unbindService(serviceConnection)
            isBoundToCompositorService = false
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    private external fun createSocketFd(socketDir: String): Int
    private external fun acceptSocket(): Int
}
