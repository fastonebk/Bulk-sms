package np.baraprinthouse.bphbulksms

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.view.View
import android.widget.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    data class Contact(val phone: String, val name: String = "")
    private data class QueueItem(val contact: Contact, var state: String = "Pending")
    private val contacts = mutableListOf<Contact>()
    private val queue = mutableListOf<QueueItem>()
    private var subscriptions: List<SubscriptionInfo> = emptyList()
    private var selectedSubId: Int? = null
    private var sending = false
    private var paused = false
    private val stopFlag = AtomicBoolean(false)
    private var messageBox: EditText? = null
    private var statusView: TextView? = null
    private var listView: TextView? = null
    private var simSpinner: Spinner? = null
    private var consentBox: CheckBox? = null
    private var sendButton: Button? = null
    private var pauseButton: Button? = null
    private var receiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshSims()
        registerStatusReceiver()
    }

    override fun onDestroy() {
        if (receiverRegistered) try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            text = "BPH BULK SMS"
            textSize = 25f; setTextColor(0xFF172554.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Standalone Ncell / Android SIM SMS sender"
            textSize = 13f; setTextColor(0xFF64748B.toInt())
        })
        val import = Button(this).apply {
            text = "1. Import CSV contacts"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "text/*"
                }
                startActivityForResult(i, REQUEST_CSV)
            }
        }
        root.addView(import)
        root.addView(TextView(this).apply { text = "2. Choose SIM"; textSize = 16f })
        simSpinner = Spinner(this)
        root.addView(simSpinner)
        root.addView(TextView(this).apply { text = "3. Write your SMS"; textSize = 16f })
        messageBox = EditText(this).apply {
            hint = "Type your message…"; minLines = 4; maxLines = 7; gravity =  topLeft()
        }
        root.addView(messageBox)
        consentBox = CheckBox(this).apply {
            text = "I confirm all recipients opted in and this message identifies the sender."
        }
        root.addView(consentBox)
        statusView = TextView(this).apply {
            text = "Import a CSV to begin."; textSize = 14f; setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(statusView)
        sendButton = Button(this).apply {
            text = "Review and Send"; setOnClickListener { reviewAndSend() }
        }
        root.addView(sendButton)
        pauseButton = Button(this).apply {
            text = "Pause"; isEnabled = false; setOnClickListener {
                paused = !paused; text = if (paused) "Resume" else "Pause"
                updateStatus()
            }
        }
        root.addView(pauseButton)
        root.addView(Button(this).apply {
            text = "Stop campaign"; setOnClickListener { stopFlag.set(true); paused = false; updateStatus() }
        })
        root.addView(TextView(this).apply {
            text = "Reports (latest 30):"; textSize = 16f; setPadding(0, dp(16), 0, dp(6))
        })
        listView = TextView(this).apply { textSize = 12f }
        root.addView(listView)
        root.addView(TextView(this).apply {
            text = "Important: SMS charges and Ncell limits apply. Submitted means the phone accepted the request, not guaranteed delivery. Test with your own number first."
            textSize = 12f; setTextColor(0xFF92400E.toInt()); setPadding(0, dp(16), 0, 0)
        })
        setContentView(scroll)
    }

    private fun topLeft() = android.view.Gravity.TOP or android.view.Gravity.START
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun refreshSims() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQUEST_PHONE);
            return
        }
        try {
            val sm = getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            subscriptions = sm.activeSubscriptionInfoList ?: emptyList()
            val labels = subscriptions.map {
                val carrier = it.carrierName?.toString() ?: "SIM"
                "$carrier · SIM ${it.simSlotIndex + 1}"
            }.ifEmpty { listOf("Default SIM (Android choice)") }
            simSpinner?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            simSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) { selectedSubId = null }
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedSubId = subscriptions.getOrNull(position)?.subscriptionId
                }
            }
        } catch (e: Exception) {
            simSpinner?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Default SIM"))
            statusView?.text = "Could not read SIM list. Default SIM may be used. ${e.message}"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQUEST_PHONE) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) refreshSims()
            else statusView?.text = "Phone-state permission denied. SIM list may be unavailable."
        }
        if (requestCode == REQUEST_SMS) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) confirmAndStart()
            else statusView?.text = "SMS permission denied. No messages were sent."
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CSV && resultCode == Activity.RESULT_OK && data?.data != null) {
            try {
                contentResolver.openInputStream(data.data!!)?.bufferedReader()?.use { parseCsv(it.readText()) }
            } catch (e: Exception) { statusView?.text = "CSV error: ${e.message}" }
        }
    }

    private fun parseCsv(raw: String) {
        val lines = raw.removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
        require(lines.size >= 2) { "CSV must include a header and at least one contact." }
        val header = splitCsv(lines.first()).map { it.trim().lowercase(Locale.ROOT) }
        val phoneIndex = header.indexOf("phone").takeIf { it >= 0 } ?: header.indexOf("mobile").takeIf { it >= 0 }
            ?: throw IllegalArgumentException("CSV needs a phone or mobile column.")
        val nameIndex = header.indexOf("name")
        val seen = HashSet<String>()
        contacts.clear()
        var invalid = 0; var duplicates = 0
        lines.drop(1).forEach { line ->
            val cols = splitCsv(line)
            val phone = normalize(cols.getOrNull(phoneIndex).orEmpty())
            if (phone == null) invalid++
            else if (!seen.add(phone)) duplicates++
            else contacts.add(Contact(phone, cols.getOrNull(nameIndex).orEmpty()))
        }
        queue.clear()
        queue.addAll(contacts.map { QueueItem(it) })
        statusView?.text = "Loaded ${contacts.size} unique numbers · $duplicates duplicates removed · $invalid invalid rows."
        updateReport()
    }

    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (quoted && i + 1 < line.length && line[i + 1] == '"') { cur.append('"'); i++ }
                else quoted = !quoted
            } else if (c == ',' && !quoted) { out.add(cur.toString().trim()); cur.setLength(0) }
            else cur.append(c)
            i++
        }
        out.add(cur.toString().trim())
        return out
    }

    private fun normalize(raw: String): String? {
        var p = raw.trim().replace(Regex("[^0-9+]"), "")
        if (p.startsWith("00977")) p = "+${p.drop(2)}"
        if (p.startsWith("977")) p = "+$p"
        if (p.startsWith("0") && p.length == 10) p = "+977${p.drop(1)}"
        if (Regex("^9[678][0-9]{8}$").matches(p)) p = "+977$p"
        return p.takeIf { Regex("^\\+9779[678][0-9]{8}$").matches(it) }
    }

    private fun reviewAndSend() {
        val msg = messageBox?.text?.toString()?.trim().orEmpty()
        if (queue.isEmpty()) { statusView?.text = "Import contacts first."; return }
        if (msg.isEmpty()) { statusView?.text = "Write a message first."; return }
        if (consentBox?.isChecked != true) { statusView?.text = "Confirm recipient consent before sending."; return }
        if (sending) { statusView?.text = "A campaign is already running."; return }
        AlertDialog.Builder(this)
            .setTitle("Review campaign")
            .setMessage("Recipients: ${queue.size}\nMessage:\n$msg\n\nSMS charges may apply. Start sending at a paced rate?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.SEND_SMS), REQUEST_SMS)
                else confirmAndStart()
            }.show()
    }

    private fun confirmAndStart() {
        val msg = messageBox?.text?.toString()?.trim().orEmpty()
        if (msg.isEmpty() || queue.isEmpty()) return
        sending = true; paused = false; stopFlag.set(false)
        sendButton?.isEnabled = false; pauseButton?.isEnabled = true
        Thread {
            for ((index, item) in queue.withIndex()) {
                while (paused && !stopFlag.get()) Thread.sleep(300)
                if (stopFlag.get()) break
                if (item.state != "Pending") continue
                try {
                    val sms = if (Build.VERSION.SDK_INT >= 31 && selectedSubId != null)
                        getSystemService(SmsManager::class.java).createForSubscriptionId(selectedSubId!!)
                    else @Suppress("DEPRECATION") SmsManager.getDefault()
                    val parts = sms.divideMessage(msg)
                    val sentIntents = ArrayList<PendingIntent>()
                    parts.forEachIndexed { partIndex, _ ->
                        val intent = Intent(this, SmsStatusReceiver::class.java).apply {
                            putExtra("phone", item.contact.phone); putExtra("kind", "sent")
                            putExtra("part", partIndex)
                        }
                        sentIntents.add(PendingIntent.getBroadcast(this, index * 100 + partIndex,
                            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    }
                    if (parts.size == 1) sms.sendTextMessage(item.contact.phone, null, msg, sentIntents[0], null)
                    else sms.sendMultipartTextMessage(item.contact.phone, null, parts, sentIntents, null)
                    item.state = "Submitted (pending network result)"
                } catch (e: Exception) { item.state = "Failed: ${e.message ?: "send error"}" }
                runOnUiThread { updateStatus(); updateReport() }
                // Conservative pacing to reduce burst traffic. Carrier limits still apply.
                if (index < queue.lastIndex) Thread.sleep(5000)
            }
            sending = false
            runOnUiThread {
                sendButton?.isEnabled = true; pauseButton?.isEnabled = false
                statusView?.text = if (stopFlag.get()) "Stopped by user. " + summary()
                    else "Queue finished. " + summary()
                updateReport()
            }
        }.start()
    }

    private fun summary(): String {
        val submitted = queue.count { it.state.startsWith("Submitted") }
        val failed = queue.count { it.state.startsWith("Failed") }
        val pending = queue.count { it.state == "Pending" }
        return "Submitted: $submitted · Failed: $failed · Pending: $pending"
    }
    private fun updateStatus() { statusView?.text = (if (paused) "Paused. " else if (sending) "Sending. " else "") + summary() }
    private fun updateReport() {
        listView?.text = queue.take(30).mapIndexed { i, q -> "${i + 1}. ${q.contact.phone} — ${q.state}" }.joinToString("\n")
            .ifEmpty { "No campaign loaded." }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val phone = intent?.getStringExtra("phone") ?: return
            val kind = intent.getStringExtra("kind") ?: return
            val ok = intent.getBooleanExtra("ok", false)
            val item = queue.firstOrNull { it.contact.phone == phone } ?: return
            if (kind == "sent") item.state = if (ok) "Submitted" else "Failed (network/phone error)"
            runOnUiThread { updateStatus(); updateReport() }
        }
    }
    private fun registerStatusReceiver() {
        val filter = IntentFilter(SmsStatusReceiver.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(statusReceiver, filter)
        receiverRegistered = true
    }

    companion object {
        private const val REQUEST_CSV = 10
        private const val REQUEST_PHONE = 11
        private const val REQUEST_SMS = 12
    }
}
