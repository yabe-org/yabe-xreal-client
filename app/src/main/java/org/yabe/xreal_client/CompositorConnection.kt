package org.yabe.xreal_client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.FileObserver
import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.yabe.xreal.ICompositorHost

// connection to CompositorHost; sends config text, including its later
// changes, and client fds
internal class CompositorConnection(
    private val context: Context,
    private val rootfs: RootfsEnvironment,
    private val log: (String) -> Unit,
    // both are called on the main thread
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
) {
    companion object {
        private const val COMPOSITOR_PACKAGE = "org.yabe.xreal"
        private const val COMPOSITOR_SERVICE = "$COMPOSITOR_PACKAGE.CompositorService"
    }

    @Volatile
    private var compositorHost: ICompositorHost? = null
    private var isBound = false
    private var configObserver: FileObserver? = null

    val isConnected: Boolean
        get() = compositorHost != null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            log("CompositorService connected")
            compositorHost = ICompositorHost.Stub.asInterface(service)
            onConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            log("CompositorService disconnected")
            onDisconnected()
        }
    }

    fun bindIfNeeded() {
        if (isBound) {
            return
        }
        val intent = Intent().setComponent(
            ComponentName(COMPOSITOR_PACKAGE, COMPOSITOR_SERVICE),
        )
        val success = context.bindService(intent, serviceConnection, 0)
        isBound = success
        log("bindService() ${if (success) "succeeded" else "failed"}")
    }

    fun unbind() {
        compositorHost = null
        if (!isBound) {
            return
        }

        try {
            context.unbindService(serviceConnection)
        } finally {
            isBound = false
        }
    }

    // also starts watching the config file to send its changes
    fun sendConfig(): Boolean {
        val host = compositorHost ?: return false
        try {
            host.setConfigText(rootfs.readConfig())
        } catch (e: Exception) {
            log("Failed to send config text: ${e.message}")
            return false
        }
        startConfigObserverIfNeeded()
        return true
    }

    fun isCompositorReady(): Boolean {
        val host = compositorHost ?: return false
        return try {
            host.isCompositorReady
        } catch (e: Exception) {
            log("Failed to check compositor readiness: ${e.message}")
            false
        }
    }

    // takes the ownership of acceptedFd
    fun sendClientFd(acceptedFd: Int) {
        val host = compositorHost
        val pfd = ParcelFileDescriptor.adoptFd(acceptedFd)
        if (host == null) {
            log("CompositorService is not connected; dropping fd: $acceptedFd")
            pfd.close()
            return
        }
        try {
            pfd.use {
                host.addClientFd(it)
            }
            log("Sent client fd to compositor: $acceptedFd")
        } catch (e: Exception) {
            log("Failed to send client fd to compositor: ${e.message}")
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
                    return
                }
                val host = compositorHost
                if (host == null) {
                    log("CompositorService is not connected; config change is not sent")
                    return
                }
                try {
                    host.setConfigText(rootfs.readConfig())
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

    @Synchronized
    fun stopConfigObserver() {
        configObserver?.stopWatching()
        configObserver = null
    }
}
