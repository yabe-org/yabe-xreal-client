package org.yabe.xreal_client

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.FileObserver
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
    // tracer pids of chroot environments started by this service
    private val tracerPids = mutableSetOf<Int>()
    private var configObserver: FileObserver? = null
    // commands requested before DBus is started; guarded by itself
    private val deferredCommands = mutableListOf<Array<String>>()
    private var isDbusStartupFinished = false

    private val binder = object : ICompositorService.Stub() {
        override fun isCompositorReady(): Boolean {
            log("Unexpected call from compositor: isCompositorReady()")
            return false
        }

        override fun setConfigText(configText: String?) {
            log("Unexpected call from compositor: setConfigText()")
        }

        override fun addClientFd(pfd: ParcelFileDescriptor?) {
            log("Unexpected call from compositor: addClient()")
        }

        override fun notifyCompositorReady() {
            log("Received notification that compositor is started")
            connectToRunningCompositor()
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

        // returns 0 if the command is deferred until DBus is started
        override fun exec(argv: Array<out String>?): Int {
            if (argv.isNullOrEmpty()) {
                return -1
            }
            synchronized(deferredCommands) {
                if (!isDbusStartupFinished) {
                    deferredCommands.add(arrayOf(*argv))
                    return 0
                }
            }
            return execInRootfs(arrayOf(*argv))
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
                startConfigObserverIfNeeded()
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

    @Synchronized
    private fun startConfigObserverIfNeeded() {
        if (configObserver != null) {
            return
        }
        val configFile = rootfs.configFile
        val directory = configFile.parentFile ?: return
        // FileObserver(File, Int) requires API 29
        @Suppress("DEPRECATION")
        val observer = object : FileObserver(
            directory.absolutePath,
            CLOSE_WRITE or MOVED_TO,
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path != configFile.name) {
                    return;
                }
                val service = compositorService
                if (service == null) {
                    log("CompositorService is not connected; config change is not sent")
                    return
                }
                try {
                    service.setConfigText(rootfs.readConfig())
                    log("Sent updated config text to compositor")
                } catch (e: Exception) {
                    log("Failed to send updated config text: ${e.message}")
                }
            }
        }
        observer.startWatching()
        configObserver = observer
        log("Started watching config file: ${configFile.absolutePath}")
    }

    private fun stopConfigObserver() {
        synchronized(this) {
            configObserver?.stopWatching()
            configObserver = null
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

    private fun execInRootfs(argv: Array<String>): Int {
        if (!rootfs.ensureExtracted()) {
            return -1
        }
        log("Executing: ${argv.joinToString(" ")}")
        val pid = ChrootNative.start_chroot_func(
            rootfs.path,
            AndlifyExecRunnable(argv),
            OsConstants.STDIN_FILENO,
            OsConstants.STDOUT_FILENO,
            OsConstants.STDERR_FILENO
        )
        if (pid > 0) {
            synchronized(tracerPids) { tracerPids.add(pid) }
        }
        return pid
    }

    // runs deferred commands after DBus startup finishes, even if it fails, so
    // that configured commands are not lost
    private fun runDeferredCommandsAfterDbusStartup(readDescriptor: ParcelFileDescriptor) {
        executor.execute {
            val status = ParcelFileDescriptor.AutoCloseInputStream(readDescriptor).use {
                it.read()
            }
            if (status == '1'.code) {
                log("DBus session bus is started")
            } else {
                log("Failed to start DBus session bus; deferred commands run without it")
            }
            val commands = synchronized(deferredCommands) {
                isDbusStartupFinished = true
                deferredCommands.toList().also { deferredCommands.clear() }
            }
            commands.forEach(::execInRootfs)
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
        log("Setting file owner...")
        if (!ChrootNative.set_file_owner(rootfs.path, "/tmp/wayland-1", 1000, 1000, 0b110_000_000)) {
            log("Failed to assign Wayland socket ownership")
            return false
        }

        val stdinPipe = ParcelFileDescriptor.createPipe()
        val stdoutPipe = ParcelFileDescriptor.createPipe()
        val stderrPipe = ParcelFileDescriptor.createPipe()
        consumeOutput(stdoutPipe[0], captureForReport = false)
        consumeOutput(stderrPipe[0], captureForReport = true)
        val dbusReadyPipe = ParcelFileDescriptor.createPipe()
        runDeferredCommandsAfterDbusStartup(dbusReadyPipe[0])
        log("Chroot starting...")
        val newPid = ChrootNative.start_chroot_func(
            rootfs.path,
            AndlifyEntrypointRunnable(dbusReadyPipe[1].fd),
            stdinPipe[0].fd,
            stdoutPipe[1].fd,
            stderrPipe[1].fd,
            uid = 0,
            gid = 0,
        )
        stdinPipe.forEach(ParcelFileDescriptor::close)
        stdoutPipe[1].close()
        stderrPipe[1].close()
        dbusReadyPipe[1].close()
        if (newPid <= 0) {
            log("Failed to start chroot: $newPid")
            return false
        }
        synchronized(tracerPids) { tracerPids.add(newPid) }
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

    // The process may outlive this service, so the chroot environment must not
    // survive it; otherwise the next startClient() clears /run under running
    // daemons, and andlify skips recreating /run/user/1000 while their tracers
    // still hold the ownership store.
    private fun stopChroots() {
        val pids = synchronized(tracerPids) {
            tracerPids.toList().also { tracerPids.clear() }
        }
        pids.forEach(ChrootNative::stop_chroot)
    }

    override fun onDestroy() {
        log("CompositorService onDestroy")
        stopConfigObserver()
        stopChroots()
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
