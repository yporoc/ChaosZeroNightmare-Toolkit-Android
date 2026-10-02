package yporoc.czntoolkit.patcher.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import yporoc.czntoolkit.patcher.service.IPatchService
import yporoc.czntoolkit.patcher.service.PatchService
import rikka.shizuku.Shizuku

sealed interface ShizukuAvail {
    data object NotInstalled : ShizukuAvail
    data object NotRunning : ShizukuAvail
    data object NoPermission : ShizukuAvail
    data object Ready : ShizukuAvail
}

/** Shizuku 可用性判定与 UserService 绑定封装。 */
object ShizukuHelper {
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: Throwable) {
        false
    }

    fun avail(context: Context): ShizukuAvail = try {
        when {
            !isInstalled(context) -> ShizukuAvail.NotInstalled
            !Shizuku.pingBinder() -> ShizukuAvail.NotRunning
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuAvail.Ready
            else -> ShizukuAvail.NoPermission
        }
    } catch (_: Throwable) {
        ShizukuAvail.NotRunning
    }

    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    fun bind(
        context: Context,
        onConnected: (IPatchService) -> Unit,
        onDisconnected: () -> Unit,
    ): ServiceConnection {
        val args = Shizuku.UserServiceArgs(ComponentName(context, PatchService::class.java))
            .processNameSuffix("czn-patch")
            .version(1)
            .daemon(false)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    onConnected(IPatchService.Stub.asInterface(binder))
                } else {
                    onDisconnected()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                onDisconnected()
            }
        }
        Shizuku.bindUserService(args, conn)
        return conn
    }

    fun unbind(context: Context, conn: ServiceConnection) {
        val args = Shizuku.UserServiceArgs(ComponentName(context, PatchService::class.java))
            .version(1)
        runCatching { Shizuku.unbindUserService(args, conn, true) }
    }
}
