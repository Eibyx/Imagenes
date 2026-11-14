package dev.mtzforge.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.mtzforge.app.BuildConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/** Estado y conexión con Shizuku (API oficial dev.rikka.shizuku). */
class ShizukuManager(private val context: Context) {

    enum class Status(val label: String) {
        NOT_INSTALLED("Shizuku no está instalado"),
        NOT_RUNNING("Shizuku no está en ejecución"),
        NO_PERMISSION("Falta conceder permiso a MTZ Forge"),
        READY("Shizuku listo"),
    }

    private val _status = MutableStateFlow(Status.NOT_RUNNING)
    val status: StateFlow<Status> = _status

    private var service: IFileService? = null
    private var connection: ServiceConnection? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { service = null; refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        connection?.let { runCatching { Shizuku.unbindUserService(args(), it, true) } }
        connection = null
        service = null
    }

    fun refresh() {
        _status.value = when {
            !isInstalled() -> Status.NOT_INSTALLED
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> Status.NOT_RUNNING
            Shizuku.isPreV11() -> Status.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NO_PERMISSION
            else -> Status.READY
        }
    }

    fun requestPermission() {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) Shizuku.requestPermission(REQUEST_CODE)
        refresh()
    }

    fun openShizukuApp(): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG) ?: return false
        context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun isInstalled() = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true }.getOrDefault(false)

    private fun args() = Shizuku.UserServiceArgs(ComponentName(context.packageName, FileService::class.java.name))
        .daemon(false)
        .processNameSuffix("files")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    /** Conecta (o reutiliza) el UserService. Lanza excepción si Shizuku no está listo. */
    suspend fun fileService(): IFileService {
        refresh()
        check(status.value == Status.READY) { status.value.label }
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        return withTimeout(15_000) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder == null || !binder.pingBinder()) {
                            if (cont.isActive) cont.resumeWithException(IllegalStateException("El servicio de Shizuku no responde"))
                            return
                        }
                        val s = IFileService.Stub.asInterface(binder)
                        service = s
                        if (cont.isActive) cont.resume(s)
                    }
                    override fun onServiceDisconnected(name: ComponentName?) { service = null }
                }
                connection = conn
                Shizuku.bindUserService(args(), conn)
            }
        }
    }

    companion object {
        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 4242
    }
}
