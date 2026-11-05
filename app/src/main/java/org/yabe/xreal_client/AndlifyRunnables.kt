package org.yabe.xreal_client

// dbusReadyFd receives one byte, '1' or '0', after starting the DBus session
// bus succeeds or fails
internal class AndlifyEntrypointRunnable(
    private val dbusReadyFd: Int,
) : Runnable {
    override fun run() = andlifyEntrypoint(dbusReadyFd)

    private external fun andlifyEntrypoint(dbusReadyFd: Int)
}

internal class AndlifyExecRunnable(
    private val argv: Array<String>,
) : Runnable {
    override fun run() = andlifyExec(argv)

    private external fun andlifyExec(argv: Array<String>)
}
