package org.yabe.xreal_client

import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.andlify.library.ChrootNative
import java.io.File
import java.util.concurrent.ExecutorService

// chroot environment in the rootfs where DBus and configured commands run
internal class ClientEnvironment(
    private val rootfs: RootfsEnvironment,
    private val filesDirectory: File,
    private val executor: ExecutorService,
    private val log: (String) -> Unit,
) {
    private val stderrBuilder = StringBuffer()
    // tracer pids of chroot environments started by this instance
    private val tracerPids = mutableSetOf<Int>()
    // commands requested before DBus is started; guarded by itself
    private val deferredCommands = mutableListOf<Array<String>>()
    private var isDbusStartupFinished = false

    private val stderrFile
        get() = File(filesDirectory, "stderr.txt")

    // clears stderr.txt and tmpfs directories in the rootfs
    fun prepare(): Boolean {
        try {
            stderrFile.writeText("")
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
        return true
    }

    // starts the init-like entrypoint, which starts DBus
    fun start(): Boolean {
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
            tracerPids.toList().also { tracerPids.clear() }
        }
        pids.forEach(ChrootNative::stop_chroot)
        saveAndLogStderr()
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

    private external fun clearRootfsTmpfsDirectories(rootfsPath: String): Boolean
}
