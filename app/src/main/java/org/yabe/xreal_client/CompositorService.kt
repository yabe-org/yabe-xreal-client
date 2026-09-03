package org.yabe.xreal_client

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import com.andlify.library.ChrootNative
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.yabe.xreal.ICompositorService

class CompositorService : Service() {
    fun interface LogListener {
        fun appendLog(message: String)
    }

    companion object {
        private const val TAG = "YabeXrealClient"
        private const val COMPOSITOR_PACKAGE = "org.yabe.xreal"
        private const val COMPOSITOR_SERVICE = "$COMPOSITOR_PACKAGE.CompositorService"
        var listener: LogListener? = null

        init {
            System.loadLibrary("xreal_client")
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        listener?.appendLog(message)
    }

    private var compositorService: ICompositorService? = null
    private var isBoundToCompositorService = false
    private var isClientStarted = false
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val stderrBuilder = StringBuffer()
    private val rootfs by lazy { RootfsEnvironment(filesDir, assets, ::log) }

    private val binder = object : ICompositorService.Stub() {
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

        override fun createKeymap(
            rules: String?,
            model: String?,
            layout: String?,
            variant: String?,
            options: String?
        ): String? {
            if (!rootfs.ensureExtracted()) {
                return null
            }
            return createKeymap(
                "${rootfs.path}/usr/share/xkeyboard-config-2",
                rules.orEmpty(),
                model.orEmpty(),
                layout.orEmpty(),
                variant.orEmpty(),
                options.orEmpty(),
            )
        }

        override fun exec(argv: Array<out String>?): Int {
            if (argv.isNullOrEmpty() || !rootfs.ensureExtracted()) {
                return -1
            }
            return ChrootNative.start_chroot_func(
                rootfs.path,
                AndlifyExecRunnable(arrayOf(*argv)),
                OsConstants.STDIN_FILENO,
                OsConstants.STDOUT_FILENO,
                OsConstants.STDERR_FILENO
            )
        }

        override fun setConfigText(configText: String?) {
            log("Unexpected call from compositor: setConfigText()")
        }
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            log("CompositorService connected")
            compositorService = ICompositorService.Stub.asInterface(service)
            try {
                if (!rootfs.ensureExtracted()) {
                    return
                }
                val configText = rootfs.readConfig()
                val service = compositorService ?: return
                service.setConfigText(configText)
                if (service.isCompositorReady) {
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

        val intent = Intent().setComponent(
            ComponentName(COMPOSITOR_PACKAGE, COMPOSITOR_SERVICE),
        )
        val success = bindService(intent, serviceConnection, 0)
        isBoundToCompositorService = success
        log("bindService() ${if (success) "succeeded" else "failed"}")
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

    private fun saveAndLogStderr() {
        try {
            File(filesDir, "stderr.txt").writeText(stderrBuilder.toString())
        } catch (e: Exception) {
            log("Failed to save stderr to file: ${e.message}")
        }
    }

    private fun consumeOutput(
        readDescriptor: ParcelFileDescriptor,
        captureForReport: Boolean,
    ) {
        executor.execute {
            ParcelFileDescriptor.AutoCloseInputStream(readDescriptor).use { input ->
                val buffer = ByteArray(1024)
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead <= 0) {
                        break
                    }
                    val message = String(buffer, 0, bytesRead)
                    if (captureForReport) {
                        stderrBuilder.append(message)
                    }
                    log(message)
                }
            }
            if (captureForReport) {
                saveAndLogStderr()
            }
        }
    }

    private fun startClient(): Boolean {
        try {
            File(filesDir, "stderr.txt").writeText("")
        } catch (e: Exception) {
            log("Failed to init stderr file: ${e.message}")
        }
        if (!rootfs.ensureExtracted()) {
            return false
        }
        if (!clearRootfsTmpfsDirectories(rootfs.path)) {
            log("Failed to clear rootfs tmpfs directories")
            return false
        }
        val fd = createSocketFd("${rootfs.path}/tmp")
        if (fd < 0) {
            log("Failed to create socket fd")
            return false
        }

        val stdinPipe = ParcelFileDescriptor.createPipe()
        val stdoutPipe = ParcelFileDescriptor.createPipe()
        val stderrPipe = ParcelFileDescriptor.createPipe()
        consumeOutput(stdoutPipe[0], captureForReport = false)
        consumeOutput(stderrPipe[0], captureForReport = true)
        log("Chroot starting...")
        val newPid = ChrootNative.start_chroot_func(
            rootfs.path,
            AndlifyEntrypointRunnable(),
            stdinPipe[0].fd,
            stdoutPipe[1].fd,
            stderrPipe[1].fd,
        )
        stdinPipe.forEach(ParcelFileDescriptor::close)
        stdoutPipe[1].close()
        stderrPipe[1].close()
        if (newPid <= 0) {
            log("Failed to start chroot: $newPid")
            return false
        }
        log("Chroot started successfully")

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
        return true
    }

    @Synchronized
    private fun startClientIfNeeded() {
        if (isClientStarted) {
            log("Client is already started")
            return
        }
        isClientStarted = startClient()
    }

    private fun sendClientFd(acceptedFd: Int) {
        val service = compositorService
        val pfd = ParcelFileDescriptor.adoptFd(acceptedFd)
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
        return binder
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

    private external fun clearRootfsTmpfsDirectories(rootfsPath: String): Boolean
    private external fun createSocketFd(socketDir: String): Int
    private external fun acceptSocket(): Int
    private external fun createKeymap(
        includeDirectory: String,
        rules: String,
        model: String,
        layout: String,
        variant: String,
        options: String,
    ): String?
}
