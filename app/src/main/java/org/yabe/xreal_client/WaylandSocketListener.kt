package org.yabe.xreal_client

import com.andlify.library.ChrootNative
import java.util.concurrent.ExecutorService

// listens on the Wayland socket under <rootfs>/tmp and passes accepted client
// fds, because CompositorHost cannot accept() a socket created by this app
internal class WaylandSocketListener(
    private val rootfs: RootfsEnvironment,
    private val executor: ExecutorService,
    private val log: (String) -> Unit,
) {
    fun create(): Boolean {
        val fd = createSocketFd("${rootfs.path}/tmp")
        if (fd < 0) {
            log("Failed to create socket fd")
            return false
        }
        return true
    }

    // makes the socket accessible to the user in the rootfs.
    // This scans the whole rootfs to initialize the andlify ownership store
    // unless a running chroot environment has already initialized it.
    fun assignOwnership(): Boolean {
        if (!ChrootNative.set_file_owner(rootfs.path, "/tmp/wayland-1", 1000, 1000, 0b110_000_000)) {
            log("Failed to assign Wayland socket ownership")
            return false
        }
        log("Assigned Wayland socket ownership")
        return true
    }

    // onAccepted takes the ownership of the accepted fd
    fun startAccepting(onAccepted: (Int) -> Unit) {
        executor.execute {
            while (true) {
                val acceptedFd = acceptSocket()
                if (acceptedFd < 0) {
                    log("Failed to accept socket")
                    return@execute
                }
                onAccepted(acceptedFd)
            }
        }
    }

    private external fun createSocketFd(socketDir: String): Int
    private external fun acceptSocket(): Int
}
