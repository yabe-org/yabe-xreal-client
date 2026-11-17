package org.yabe.xreal_client

import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.andlify.library.ChrootNative
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

// chroot environment in the rootfs where DBus and configured commands run
internal class ClientEnvironment(
    private val rootfs: RootfsEnvironment,
    private val filesDirectory: File,
    private val executor: ExecutorService,
    private val log: (String) -> Unit,
) {
    private val stderrBuilder = StringBuffer()
    // tracer pids of chroot environments started by this instance;
    // both are guarded by tracerPids
    private val tracerPids = mutableSetOf<Int>()
    private var isStopped = false
    // commands requested before DBus is started; guarded by itself
    private val deferredCommands = mutableListOf<Array<String>>()
    private var isDbusStartupFinished = false
    // A tracer is killed when the thread that forked it exits
    // (PR_SET_PDEATHSIG), so every chroot environment is forked from this
    // thread, which lives until stop(); pooled threads exit when idle
    private val spawnExecutor = Executors.newSingleThreadExecutor()

    private val stderrFile
        get() = File(filesDirectory, "stderr.txt")

    // clears stderr.txt and tmpfs directories in the rootfs
    fun prepare(): Boolean {
        try {
            stderrFile.writeText("")
        } catch (e: Exception) {
            log("Failed to init stderr file: ${e.message}")
        }
        if (!clearRootfsTmpfsDirectories(rootfs.path)) {
            log("Failed to clear rootfs tmpfs directories")
            return false
        }
        return true
    }

    // starts the init-like entrypoint, which starts DBus.
    // beforeCommands is called in another thread after DBus startup finishes
    // and before deferred commands run
    fun start(beforeCommands: () -> Unit): Boolean {
        val stdinPipe = ParcelFileDescriptor.createPipe()
        val stdoutPipe = ParcelFileDescriptor.createPipe()
        val stderrPipe = ParcelFileDescriptor.createPipe()
        consumeOutput(stdoutPipe[0], captureForReport = false)
        consumeOutput(stderrPipe[0], captureForReport = true)
        val dbusReadyPipe = ParcelFileDescriptor.createPipe()
        runDeferredCommandsAfterDbusStartup(dbusReadyPipe[0], beforeCommands)
        log("Chroot starting...")
        val newPid = spawn {
            ChrootNative.start_chroot_func(
                rootfs.path,
                AndlifyEntrypointRunnable(dbusReadyPipe[1].fd),
                stdinPipe[0].fd,
                stdoutPipe[1].fd,
                stderrPipe[1].fd,
                uid = 0,
                gid = 0,
            )
        }
        stdinPipe.forEach(ParcelFileDescriptor::close)
        stdoutPipe[1].close()
        stderrPipe[1].close()
        dbusReadyPipe[1].close()
        if (newPid <= 0) {
            log("Failed to start chroot: $newPid")
            return false
        }
        addTracer(newPid)
        // the tracer initializes the andlify ownership store, which scans the
        // whole rootfs, before the entrypoint starts DBus
        log("Chroot started; waiting for rootfs scan and DBus startup...")
        return true
    }

    // returns 0 if the command is deferred until DBus is started
    fun exec(argv: Array<String>): Int {
        synchronized(deferredCommands) {
            if (!isDbusStartupFinished) {
                deferredCommands.add(argv)
                return 0
            }
        }
        return execInRootfs(argv)
    }

    // The process may outlive the service, so the chroot environment must not
    // survive it; otherwise the next start clears /run under running daemons,
    // and andlify skips recreating /run/user/1000 while their tracers still
    // hold the ownership store.
    fun stop() {
        val pids = synchronized(tracerPids) {
            isStopped = true
            tracerPids.toList().also { tracerPids.clear() }
        }
        pids.forEach(ChrootNative::stop_chroot)
        // tracers forked after the pids are taken are stopped by addTracer()
        // or killed when the spawn thread exits
        spawnExecutor.shutdown()
        saveAndLogStderr()
    }

    // runs startChroot on the spawn thread and returns its result, or a
    // negative value if stop() has already shut the thread down or the caller
    // is interrupted; a tracer forked after that dies with the spawn thread
    private fun spawn(startChroot: () -> Int): Int =
        try {
            spawnExecutor.submit(Callable(startChroot)).get()
        } catch (e: RejectedExecutionException) {
            -1
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        }

    // a chroot environment started in another thread after stop() is stopped
    // immediately
    private fun addTracer(pid: Int) {
        val shouldStop = synchronized(tracerPids) {
            if (!isStopped) {
                tracerPids.add(pid)
            }
            isStopped
        }
        if (shouldStop) {
            ChrootNative.stop_chroot(pid)
        }
    }

    private fun saveAndLogStderr() {
        try {
            stderrFile.writeText(stderrBuilder.toString())
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
        log("Executing: ${argv.joinToString(" ")}")
        val pid = spawn {
            ChrootNative.start_chroot_func(
                rootfs.path,
                AndlifyExecRunnable(argv),
                OsConstants.STDIN_FILENO,
                OsConstants.STDOUT_FILENO,
                OsConstants.STDERR_FILENO
            )
        }
        if (pid > 0) {
            addTracer(pid)
        }
        return pid
    }

    // runs deferred commands after DBus startup finishes, even if it fails, so
    // that configured commands are not lost
    private fun runDeferredCommandsAfterDbusStartup(
        readDescriptor: ParcelFileDescriptor,
        beforeCommands: () -> Unit,
    ) {
        executor.execute {
            val status = ParcelFileDescriptor.AutoCloseInputStream(readDescriptor).use {
                it.read()
            }
            if (status == '1'.code) {
                log("DBus session bus is started")
            } else {
                log("Failed to start DBus session bus; deferred commands run without it")
            }
            beforeCommands()
            val commands = synchronized(deferredCommands) {
                isDbusStartupFinished = true
                deferredCommands.toList().also { deferredCommands.clear() }
            }
            commands.forEach(::execInRootfs)
        }
    }

    private external fun clearRootfsTmpfsDirectories(rootfsPath: String): Boolean
}
