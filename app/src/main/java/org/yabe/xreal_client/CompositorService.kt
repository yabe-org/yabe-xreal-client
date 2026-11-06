package org.yabe.xreal_client

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import org.yabe.xreal.IClientHost

class CompositorService : Service() {
    fun interface LogListener {
        fun appendLog(message: String)
    }

    companion object {
        private const val TAG = "YabeXrealClient"
        var listener: LogListener? = null

        init {
            System.loadLibrary("xreal_client")
        }
    }

    // The client environment starts in parallel with the connection to
    // CompositorHost once the rootfs is extracted; only accepting Wayland
    // clients waits for both the environment and the compositor.
    private enum class State {
        // the connection to CompositorHost may be established meanwhile
        ExtractingRootfs,
        WaitingForConnection,
        // config text is sent; waiting for the compositor to be ready
        WaitingForCompositor,
        // the compositor is ready; waiting for the client environment to start
        WaitingForEnvironment,
        // Wayland clients are accepted; the client environment keeps running
        // even if CompositorHost is disconnected
        ClientStarted,
        Failed,
        Destroyed,
    }

    private enum class Event {
        ServiceCreated,
        RootfsExtracted,
        RootfsExtractionFailed,
        EnvironmentStarted,
        EnvironmentFailed,
        CompositorConnected,
        CompositorDisconnected,
        CompositorReadyNotified,
        ServiceDestroyed,
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        listener?.appendLog(message)
    }

    // accessed only on the main thread
    private var state = State.ExtractingRootfs
    private var isEnvironmentStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val rootfs by lazy { RootfsEnvironment(filesDir, assets, ::log) }
    private val environment by lazy { ClientEnvironment(rootfs, filesDir, executor, ::log) }
    private val socketListener by lazy { WaylandSocketListener(rootfs, executor, ::log) }
    private val connection by lazy {
        CompositorConnection(
            this,
            rootfs,
            ::log,
            onConnected = { handle(Event.CompositorConnected) },
            onDisconnected = { handle(Event.CompositorDisconnected) },
        )
    }

    private val binder = object : IClientHost.Stub() {
        // This can be called back on the main thread while it waits for
        // setConfigText(), so the event is posted instead of being handled
        // inside another one
        override fun notifyCompositorReady() {
            log("Received notification that compositor is started")
            post(Event.CompositorReadyNotified)
        }

        // called only after config text is sent, so the rootfs is extracted
        override fun createKeymap(
            rules: String?,
            model: String?,
            layout: String?,
            variant: String?,
            options: String?
        ): String? {
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
            return environment.exec(arrayOf(*argv))
        }
    }

    private fun post(event: Event) {
        mainHandler.post { handle(event) }
    }

    // called only on the main thread
    private fun handle(event: Event) {
        state = when (event) {
            Event.ServiceCreated -> {
                connection.bindIfNeeded()
                executor.execute {
                    post(
                        if (rootfs.extractIfNeeded()) Event.RootfsExtracted
                        else Event.RootfsExtractionFailed
                    )
                }
                State.ExtractingRootfs
            }

            Event.RootfsExtracted -> when (state) {
                State.ExtractingRootfs -> {
                    startEnvironment()
                    if (connection.isConnected) {
                        sendConfigAndCheckReadiness()
                    } else {
                        State.WaitingForConnection
                    }
                }
                else -> state
            }

            Event.RootfsExtractionFailed -> when (state) {
                State.Destroyed -> state
                else -> State.Failed
            }

            Event.EnvironmentStarted -> {
                isEnvironmentStarted = true
                when (state) {
                    State.WaitingForEnvironment -> startAccepting()
                    else -> state
                }
            }

            Event.EnvironmentFailed -> when (state) {
                State.Destroyed -> state
                else -> State.Failed
            }

            Event.CompositorConnected -> when (state) {
                State.WaitingForConnection -> sendConfigAndCheckReadiness()
                // reconnected after disconnection
                State.ClientStarted -> {
                    connection.sendConfig()
                    State.ClientStarted
                }
                // config text is sent after the rootfs is extracted
                else -> state
            }

            Event.CompositorDisconnected -> {
                connection.unbind()
                when (state) {
                    State.WaitingForCompositor,
                    State.WaitingForEnvironment -> State.WaitingForConnection
                    else -> state
                }
            }

            Event.CompositorReadyNotified -> when (state) {
                // readiness is checked again after config text is sent
                State.ExtractingRootfs, State.WaitingForConnection -> {
                    connection.bindIfNeeded()
                    state
                }
                State.WaitingForCompositor -> onCompositorReady()
                State.ClientStarted -> {
                    if (connection.isConnected) {
                        log("Client is already started")
                    } else {
                        connection.bindIfNeeded()
                    }
                    State.ClientStarted
                }
                else -> state
            }

            Event.ServiceDestroyed -> {
                connection.stopConfigObserver()
                environment.stop()
                connection.unbind()
                executor.shutdownNow()
                State.Destroyed
            }
        }
    }

    private fun sendConfigAndCheckReadiness(): State {
        if (connection.sendConfig() && connection.isCompositorReady()) {
            log("Compositor is already ready")
            return onCompositorReady()
        }
        return State.WaitingForCompositor
    }

    private fun onCompositorReady(): State =
        if (isEnvironmentStarted) startAccepting() else State.WaitingForEnvironment

    // the socket ownership is assigned after the chroot environment starts so
    // that the rootfs is scanned only once by its tracer, and before deferred
    // commands run so that Wayland clients can connect
    private fun startEnvironment() {
        executor.execute {
            val isStarted = try {
                environment.prepare() &&
                    socketListener.create() &&
                    environment.start(beforeCommands = { socketListener.assignOwnership() })
            } catch (e: RejectedExecutionException) {
                // the service is destroyed before the chroot environment starts
                false
            }
            post(if (isStarted) Event.EnvironmentStarted else Event.EnvironmentFailed)
        }
    }

    private fun startAccepting(): State {
        socketListener.startAccepting(connection::sendClientFd)
        return State.ClientStarted
    }

    override fun onCreate() {
        super.onCreate()
        log("CompositorService onCreate")
        handle(Event.ServiceCreated)
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
        handle(Event.ServiceDestroyed)
        super.onDestroy()
    }

    private external fun createKeymap(
        includeDirectory: String,
        rules: String,
        model: String,
        layout: String,
        variant: String,
        options: String,
    ): String?
}
