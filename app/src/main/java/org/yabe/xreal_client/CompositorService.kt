package org.yabe.xreal_client

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

    private enum class State {
        // waiting for the connection to CompositorHost
        WaitingForConnection,
        // config text is sent; waiting for the compositor to be ready
        WaitingForCompositor,
        // the client environment keeps running even if CompositorHost is
        // disconnected
        ClientStarted,
        Destroyed,
    }

    private enum class Event {
        ServiceCreated,
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
    private var state = State.WaitingForConnection
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
            mainHandler.post { handle(Event.CompositorReadyNotified) }
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
            return environment.exec(arrayOf(*argv))
        }
    }

    // called only on the main thread
    private fun handle(event: Event) {
        state = when (event) {
            Event.ServiceCreated -> {
                connection.bindIfNeeded()
                State.WaitingForConnection
            }

            Event.CompositorConnected -> when (state) {
                State.WaitingForConnection -> {
                    if (connection.sendConfig() && connection.isCompositorReady()) {
                        log("Compositor is already ready")
                        startClient()
                    } else {
                        State.WaitingForCompositor
                    }
                }
                // reconnected after disconnection
                State.ClientStarted -> {
                    connection.sendConfig()
                    State.ClientStarted
                }
                State.WaitingForCompositor, State.Destroyed -> state
            }

            Event.CompositorDisconnected -> {
                connection.unbind()
                when (state) {
                    State.WaitingForCompositor -> State.WaitingForConnection
                    else -> state
                }
            }

            Event.CompositorReadyNotified -> when (state) {
                // readiness is checked again after connected
                State.WaitingForConnection -> {
                    connection.bindIfNeeded()
                    State.WaitingForConnection
                }
                State.WaitingForCompositor -> startClient()
                State.ClientStarted -> {
                    if (connection.isConnected) {
                        log("Client is already started")
                    } else {
                        connection.bindIfNeeded()
                    }
                    State.ClientStarted
                }
                State.Destroyed -> State.Destroyed
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

    // the client can be started again by the next notification if it fails
    private fun startClient(): State {
        val isStarted = environment.prepare() &&
            socketListener.create() &&
            environment.start()
        if (!isStarted) {
            return State.WaitingForCompositor
        }
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
