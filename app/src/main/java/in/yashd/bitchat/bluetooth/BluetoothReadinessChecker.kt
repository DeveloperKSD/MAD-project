package `in`.yashd.bitchat.bluetooth

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import `in`.yashd.bitchat.mesh.BluetoothReadiness

class BluetoothReadinessChecker(private val context: Context) {
    fun check(): BluetoothReadiness {
        val packageManager = context.packageManager
        val hasBle = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        val hasPermissions = requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        return BluetoothReadiness(
            hasBle = hasBle,
            hasPermissions = hasPermissions,
            bluetoothEnabled = hasPermissions && adapter?.isEnabled == true,
            canAdvertise = hasPermissions && adapter?.bluetoothLeAdvertiser != null,
            canScan = hasPermissions && adapter?.bluetoothLeScanner != null,
            canGattServer = manager != null
        )
    }

    companion object {
        fun requiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }
}
