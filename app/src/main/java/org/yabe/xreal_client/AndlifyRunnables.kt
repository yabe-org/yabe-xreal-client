package org.yabe.xreal_client

internal class AndlifyEntrypointRunnable : Runnable {
    override fun run() = andlifyEntrypoint()

    private external fun andlifyEntrypoint()
}

internal class AndlifyExecRunnable(
    private val argv: Array<String>,
) : Runnable {
    override fun run() = andlifyExec(argv)

    private external fun andlifyExec(argv: Array<String>)
}
