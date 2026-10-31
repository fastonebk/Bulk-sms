package np.baraprinthouse.bphbulksms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val phone = intent.getStringExtra("phone") ?: return
        val kind = intent.getStringExtra("kind") ?: return
        val result = resultCode
        val ok = result == android.app.Activity.RESULT_OK
        val prefs = context.getSharedPreferences("sms_status", Context.MODE_PRIVATE)
        prefs.edit().putString("$kind:$phone", if (ok) "OK" else "FAILED:$result").apply()
        context.sendBroadcast(Intent(ACTION_STATUS).setPackage(context.packageName)
            .putExtra("phone", phone).putExtra("kind", kind).putExtra("ok", ok))
    }
    companion object { const val ACTION_STATUS = "np.baraprinthouse.bphbulksms.SMS_STATUS" }
}
