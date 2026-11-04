package fr.exemple.usbtether

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    companion object {
        private const val REQ = 43
    }

    private val listener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQ) {
            if (result == PackageManager.PERMISSION_GRANTED) toggle()
            else { toast("Permission Shizuku refusée"); finish() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Shizuku.pingBinder()) {
            toast("Shizuku n'est pas démarré : ouverture des réglages")
            openTetherSettings()
            finish()
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            toggle()
        } else {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQ)
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(listener)
        super.onDestroy()
    }

    // Le câble est-il branché à un ordinateur ?
    private fun usbBranche(): Boolean {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged == BatteryManager.BATTERY_PLUGGED_USB
    }

    // Le partage USB est-il actif ? (fonction USB rndis ou ncm)
    private fun partageActif(): Boolean {
        val out = runShell("svc usb getFunctions").second.lowercase()
        return out.contains("rndis") || out.contains("ncm")
    }

    private fun toggle() {
        Thread {
            val actif = partageActif()

            if (!actif && !usbBranche()) {
                runOnUiThread {
                    toast("🔌 Branchez d'abord le câble USB à l'ordinateur")
                    finish()
                }
                return@Thread
            }

            val cmd = if (actif) "svc usb setFunctions" else "svc usb setFunctions rndis"
            val r = runShell(cmd)
            Thread.sleep(1500)
            val maintenant = partageActif()

            runOnUiThread {
                when {
                    r.first != 0 -> toast("Échec : ${r.second}")
                    !actif && maintenant -> toast("✅ Partage USB activé")
                    actif && !maintenant -> toast("⛔ Partage USB désactivé")
                    else -> toast("Échec : état inchangé ${r.second}")
                }
                finish()
            }
        }.start()
    }

    private fun runShell(cmd: String): Pair<Int, String> = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText() +
                  p.errorStream.bufferedReader().readText()
        Pair(p.waitFor(), out.trim())
    } catch (e: Exception) {
        Pair(-1, e.toString())
    }

    private fun openTetherSettings() {
        val cibles = listOf(
            ComponentName("com.android.settings",
                "com.android.settings.Settings\$TetherSettingsActivity"),
            ComponentName("com.android.settings",
                "com.android.settings.TetherSettings")
        )
        for (c in cibles) {
            try {
                startActivity(Intent().setComponent(c)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
            }
        }
        startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
