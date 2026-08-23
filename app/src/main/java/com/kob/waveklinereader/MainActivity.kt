package com.kob.waveklinereader

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

/**
 * Talks to an FTDI FT232R adapter over USB-OTG and attempts a Honda K-Line
 * (10,400 baud) wake-up + read sequence against a motorcycle ECU.
 *
 * The wake-up bytes and data-table offsets are based on community
 * reverse-engineering of Honda K-Line ECUs in general. They are a starting
 * point, not a guarantee — the hex log at the bottom of the screen is there
 * so the exact response layout for this specific ECU/year can be confirmed
 * and the offsets in parseAndDisplay() adjusted if needed.
 */
class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private lateinit var usbManager: UsbManager
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var tvAdapterStatus: TextView
    private lateinit var tvEcuStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var tvRpm: TextView
    private lateinit var tvTps: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnRead: Button
    private lateinit var btnDisconnect: Button

    private val rxBuffer = mutableListOf<Byte>()

    data class Sample(val label: String, val bytes: ByteArray)
    private val samples = mutableListOf<Sample>()

    private lateinit var etLabel: EditText
    private lateinit var btnSaveSample: Button
    private lateinit var btnAnalyze: Button
    private lateinit var btnClearSamples: Button

    private lateinit var tvEct: TextView
    private lateinit var tvIat: TextView
    private lateinit var tvAf: TextView
    private lateinit var tvLight: TextView

    private lateinit var etOffRpm: EditText
    private lateinit var etOffTps: EditText
    private lateinit var etOffEct: EditText
    private lateinit var etOffIat: EditText
    private lateinit var etOffAf: EditText
    private lateinit var etOffLightByte: EditText
    private lateinit var etOffLightBit: EditText
    private lateinit var btnApplyOffsets: Button

    private lateinit var tvUpdateStatus: TextView
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnDownloadUpdate: Button
    private var pendingUpdateUrl: String? = null
    private var pendingDownloadId: Long = -1L

    private lateinit var prefs: android.content.SharedPreferences
    private var offRpm = 8
    private var offTps = 6
    private var offEct = 10
    private var offIat = 11
    private var offAf = 12
    private var offLightByte = 13
    private var offLightBit = 0

    companion object {
        const val ACTION_USB_PERMISSION = "com.kob.waveklinereader.USB_PERMISSION"
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { openDevice(it) }
                    } else {
                        log("ผู้ใช้ปฏิเสธสิทธิ์เข้าถึง USB")
                        tvAdapterStatus.text = "ถูกปฏิเสธสิทธิ์การเข้าถึง USB"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        tvAdapterStatus = findViewById(R.id.tvAdapterStatus)
        tvEcuStatus = findViewById(R.id.tvEcuStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        tvRpm = findViewById(R.id.tvRpm)
        tvTps = findViewById(R.id.tvTps)
        btnConnect = findViewById(R.id.btnConnect)
        btnRead = findViewById(R.id.btnRead)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        etLabel = findViewById(R.id.etLabel)
        btnSaveSample = findViewById(R.id.btnSaveSample)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        btnClearSamples = findViewById(R.id.btnClearSamples)

        tvEct = findViewById(R.id.tvEct)
        tvIat = findViewById(R.id.tvIat)
        tvAf = findViewById(R.id.tvAf)
        tvLight = findViewById(R.id.tvLight)

        etOffRpm = findViewById(R.id.etOffRpm)
        etOffTps = findViewById(R.id.etOffTps)
        etOffEct = findViewById(R.id.etOffEct)
        etOffIat = findViewById(R.id.etOffIat)
        etOffAf = findViewById(R.id.etOffAf)
        etOffLightByte = findViewById(R.id.etOffLightByte)
        etOffLightBit = findViewById(R.id.etOffLightBit)
        btnApplyOffsets = findViewById(R.id.btnApplyOffsets)

        prefs = getSharedPreferences("wave_kline_prefs", Context.MODE_PRIVATE)
        offRpm = prefs.getInt("offRpm", offRpm)
        offTps = prefs.getInt("offTps", offTps)
        offEct = prefs.getInt("offEct", offEct)
        offIat = prefs.getInt("offIat", offIat)
        offAf = prefs.getInt("offAf", offAf)
        offLightByte = prefs.getInt("offLightByte", offLightByte)
        offLightBit = prefs.getInt("offLightBit", offLightBit)

        etOffRpm.setText(offRpm.toString())
        etOffTps.setText(offTps.toString())
        etOffEct.setText(offEct.toString())
        etOffIat.setText(offIat.toString())
        etOffAf.setText(offAf.toString())
        etOffLightByte.setText(offLightByte.toString())
        etOffLightBit.setText(offLightBit.toString())

        btnApplyOffsets.setOnClickListener { applyOffsets() }

        btnConnect.setOnClickListener { connect() }
        btnRead.setOnClickListener { readEcu() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnSaveSample.setOnClickListener { saveSample() }
        btnAnalyze.setOnClickListener { analyzeSamples() }
        btnClearSamples.setOnClickListener {
            samples.clear()
            log("ล้างตัวอย่างทั้งหมดแล้ว")
        }

        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnDownloadUpdate = findViewById(R.id.btnDownloadUpdate)
        btnCheckUpdate.setOnClickListener { checkForUpdate() }
        btnDownloadUpdate.setOnClickListener { pendingUpdateUrl?.let { downloadAndInstall(it) } }
        btnDownloadUpdate.isEnabled = false

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        btnRead.isEnabled = false
        btnDisconnect.isEnabled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) { /* already unregistered */ }
        ioManager?.stop()
        try { port?.close() } catch (e: Exception) { /* ignore */ }
    }

    private fun connect() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            log("ไม่พบอุปกรณ์ FTDI ที่เสียบอยู่ ตรวจสอบสาย USB-OTG")
            tvAdapterStatus.text = "ไม่พบอุปกรณ์ — เช็คสาย USB-OTG"
            return
        }
        val driver = availableDrivers[0]
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
        } else {
            openDevice(device)
        }
    }

    private fun openDevice(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            log("ไม่รู้จักไดรเวอร์สำหรับอุปกรณ์นี้")
            return
        }
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            log("เปิดการเชื่อมต่อ USB ไม่สำเร็จ")
            tvAdapterStatus.text = "เปิดอุปกรณ์ไม่สำเร็จ"
            return
        }
        val p = driver.ports[0]
        try {
            p.open(connection)
            p.setParameters(10400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = p
            tvAdapterStatus.text = "พบอะแดปเตอร์ FTDI — เชื่อมต่อสำเร็จ"
            log("เปิดพอร์ต serial ที่ baud 10400 สำเร็จ")

            ioManager = SerialInputOutputManager(p, this)
            executor.submit(ioManager)

            btnConnect.isEnabled = false
            btnRead.isEnabled = true
            btnDisconnect.isEnabled = true
        } catch (e: Exception) {
            log("เปิดพอร์ตไม่สำเร็จ: ${e.message}")
            tvAdapterStatus.text = "เปิดพอร์ตไม่สำเร็จ"
        }
    }

    private fun disconnect() {
        ioManager?.stop()
        ioManager = null
        try { port?.close() } catch (e: Exception) { /* ignore */ }
        port = null
        tvAdapterStatus.text = "ยังไม่ได้เชื่อมต่อสาย"
        tvEcuStatus.text = "ยังไม่ได้ลองคุยกับ ECU"
        btnConnect.isEnabled = true
        btnRead.isEnabled = false
        btnDisconnect.isEnabled = false
        log("ตัดการเชื่อมต่อแล้ว")
    }

    private fun readEcu() {
        val p = port ?: return
        tvEcuStatus.text = "กำลังลองปลุก ECU..."
        rxBuffer.clear()
        try {
            // Wake-up pattern
            writeBytes(byteArrayOf(0xFE.toByte(), 0x04, 0xFF.toByte(), 0xFF.toByte()))
            Thread.sleep(200)

            // Init frame
            val initFrame = byteArrayOf(0x72, 0x05, 0x00, 0xF0.toByte())
            writeBytes(appendChecksum(initFrame))
            Thread.sleep(200)

            // Request data table 0x11 (RPM / TPS / ECT / IAT block on many Honda ECUs)
            val reqFrame = byteArrayOf(0x72, 0x05, 0x71, 0x11)
            writeBytes(appendChecksum(reqFrame))

            mainHandler.postDelayed({
                if (rxBuffer.isEmpty()) {
                    tvEcuStatus.text = "ไม่มีการตอบกลับจาก ECU — เช็คขั้ว K-Line และสถานะกุญแจ"
                } else {
                    tvEcuStatus.text = "ECU ตอบสนอง"
                    parseAndDisplay(rxBuffer.toByteArray())
                }
            }, 1500)
        } catch (e: Exception) {
            log("เกิดข้อผิดพลาดระหว่างอ่าน ECU: ${e.message}")
        }
    }

    private fun appendChecksum(frame: ByteArray): ByteArray {
        val sum = frame.sumOf { it.toInt() and 0xFF }
        val cs = ((0x100 - (sum % 0x100)) % 0x100).toByte()
        return frame + cs
    }

    private fun writeBytes(bytes: ByteArray) {
        port?.write(bytes, 500)
        log("TX: " + bytes.joinToString(" ") { String.format("%02X", it) })
    }

    // Called on a background thread by SerialInputOutputManager
    override fun onNewData(data: ByteArray?) {
        data ?: return
        rxBuffer.addAll(data.toList())
        mainHandler.post {
            log("RX: " + data.joinToString(" ") { String.format("%02X", it) })
        }
    }

    override fun onRunError(e: Exception?) {
        mainHandler.post { log("Serial error: ${e?.message}") }
    }

    // Uses the configurable offsets set in the "ตั้งค่า offset" section so
    // values can be corrected live from the auto-diff results without
    // rebuilding the app.
    private fun parseAndDisplay(bytes: ByteArray) {
        if (bytes.size < 10) {
            log("ได้รับข้อมูลแต่สั้นเกินไปที่จะตีความ ดู log ด้านบนประกอบ")
            return
        }

        fun byteAt(offset: Int): Int? =
            if (offset in bytes.indices) bytes[offset].toInt() and 0xFF else null

        val rpmHi = byteAt(offRpm)
        val rpmLo = byteAt(offRpm + 1)
        val rpm = if (rpmHi != null && rpmLo != null) (rpmHi shl 8) or rpmLo else null
        val tps = byteAt(offTps)
        val ect = byteAt(offEct)
        val iat = byteAt(offIat)
        val af = byteAt(offAf)
        val lightByte = byteAt(offLightByte)
        val lightOn = lightByte != null && offLightBit in 0..7 &&
            (lightByte and (1 shl offLightBit)) != 0

        mainHandler.post {
            tvRpm.text = if (rpm != null && rpm in 1..19999) rpm.toString() else "--"
            tvTps.text = tps?.toString() ?: "--"
            tvEct.text = ect?.toString() ?: "--"
            tvIat.text = iat?.toString() ?: "--"
            tvAf.text = af?.toString() ?: "--"
            tvLight.text = when {
                lightByte == null -> "ไม่มีข้อมูล"
                lightOn -> "ไฟติด (bit $offLightBit ของ byte $offLightByte)"
                else -> "ไฟดับ"
            }
        }
    }

    // Reads offsets from the input fields, saves them so they persist
    // across app restarts, and re-parses the last ECU response with the
    // new values immediately.
    private fun applyOffsets() {
        fun readInt(et: EditText, fallback: Int): Int =
            et.text.toString().trim().toIntOrNull() ?: fallback

        offRpm = readInt(etOffRpm, offRpm)
        offTps = readInt(etOffTps, offTps)
        offEct = readInt(etOffEct, offEct)
        offIat = readInt(etOffIat, offIat)
        offAf = readInt(etOffAf, offAf)
        offLightByte = readInt(etOffLightByte, offLightByte)
        offLightBit = readInt(etOffLightBit, offLightBit)

        prefs.edit()
            .putInt("offRpm", offRpm)
            .putInt("offTps", offTps)
            .putInt("offEct", offEct)
            .putInt("offIat", offIat)
            .putInt("offAf", offAf)
            .putInt("offLightByte", offLightByte)
            .putInt("offLightBit", offLightBit)
            .apply()

        log("บันทึก offset ใหม่แล้ว: RPM=$offRpm TPS=$offTps ECT=$offEct IAT=$offIat AF=$offAf Light=byte$offLightByte/bit$offLightBit")

        if (rxBuffer.isNotEmpty()) {
            parseAndDisplay(rxBuffer.toByteArray())
        }
    }

    // Stores the most recent successful ECU read under a label the user
    // provides (e.g. "ปล่อยคันเร่ง", "บิดสุด", "เดินเบา") so it can later
    // be compared against other labeled samples to auto-detect which byte
    // offset corresponds to which sensor value.
    private fun saveSample() {
        if (rxBuffer.isEmpty()) {
            log("ยังไม่มีข้อมูลจากการอ่าน ECU ล่าสุด กด \"ลองอ่าน ECU\" ก่อน")
            return
        }
        val label = etLabel.text.toString().trim().ifEmpty { "ตัวอย่าง ${samples.size + 1}" }
        samples.add(Sample(label, rxBuffer.toByteArray()))
        log("บันทึกตัวอย่าง \"$label\" แล้ว (${rxBuffer.size} bytes) — มีทั้งหมด ${samples.size} ตัวอย่าง")
        etLabel.text?.clear()
    }

    // Compares every pair of saved samples byte-by-byte and reports which
    // offsets differ. A byte position that consistently changes between
    // states like "ปล่อยคันเร่ง" and "บิดสุด" is a strong candidate for
    // being that sensor's offset in the response frame.
    private fun analyzeSamples() {
        if (samples.size < 2) {
            log("ต้องมีอย่างน้อย 2 ตัวอย่างถึงจะเปรียบเทียบได้ (มีอยู่ ${samples.size})")
            return
        }
        log("── เริ่มวิเคราะห์ ${samples.size} ตัวอย่าง ──")

        val minLen = samples.minOf { it.bytes.size }
        if (minLen == 0) {
            log("ตัวอย่างบางชุดว่างเปล่า วิเคราะห์ไม่ได้")
            return
        }

        for (i in samples.indices) {
            for (j in i + 1 until samples.size) {
                val a = samples[i]
                val b = samples[j]
                val len = minOf(a.bytes.size, b.bytes.size)
                val diffs = mutableListOf<String>()
                for (k in 0 until len) {
                    val va = a.bytes[k].toInt() and 0xFF
                    val vb = b.bytes[k].toInt() and 0xFF
                    if (va != vb) {
                        diffs.add("byte[$k]: %02X → %02X".format(va, vb))
                    }
                }
                log("\"${a.label}\" vs \"${b.label}\":")
                if (diffs.isEmpty()) {
                    log("  ไม่มี byte ไหนต่างกันเลย — สองสถานะนี้ให้ผลเหมือนกันทุกตัว (อาจไม่ใช่ข้อมูลจริง หรือค่าที่วัดไม่เปลี่ยนจริง)")
                } else {
                    diffs.forEach { log("  $it") }
                }
            }
        }

        // Highlight offsets that differ in EVERY pairwise comparison —
        // these are the best candidates to actually be a live sensor value.
        val candidateOffsets = (0 until minLen).filter { k ->
            samples.all { s -> k < s.bytes.size } &&
            samples.map { it.bytes[k] }.toSet().size > 1
        }
        if (candidateOffsets.isNotEmpty()) {
            log("── ตัวเก็ง: byte offset ที่เปลี่ยนค่าในทุกตัวอย่าง → ${candidateOffsets.joinToString(", ")} ──")
            log("ลองนำ offset เหล่านี้ไปปรับใน parseAndDisplay() เพื่อทดสอบ")
        } else {
            log("── ไม่มี offset ไหนเปลี่ยนค่าในทุกตัวอย่างเลย ลองเก็บตัวอย่างเพิ่ม หรือเช็คว่า wake-up sequence ทำงานถูกต้องไหม ──")
        }
    }

    // Calls the GitHub Releases API for this repo, compares the tag against
    // the currently installed version, and enables the download button if
    // a newer build is available on the "latest" release.
    private fun checkForUpdate() {
        tvUpdateStatus.text = "กำลังตรวจสอบ..."
        btnDownloadUpdate.isEnabled = false
        pendingUpdateUrl = null

        executor.submit {
            try {
                val url = java.net.URL("https://api.github.com/repos/akm391mnm/Kline-reader-project/releases/latest")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "WaveKlineReader")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                val code = conn.responseCode
                if (code != 200) {
                    mainHandler.post { tvUpdateStatus.text = "ตรวจสอบไม่สำเร็จ (HTTP $code)" }
                    return@submit
                }

                val body = conn.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(body)
                val publishedAt = json.optString("published_at", "")
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url")
                            break
                        }
                    }
                }

                mainHandler.post {
                    if (apkUrl == null) {
                        tvUpdateStatus.text = "ไม่พบไฟล์ APK ใน release ล่าสุด"
                        return@post
                    }
                    // "latest" is a moving tag, so we compare by published time
                    // against the last-seen build time instead of version numbers.
                    val lastSeen = prefs.getString("lastSeenReleaseTime", "")
                    if (publishedAt.isNotEmpty() && publishedAt == lastSeen) {
                        tvUpdateStatus.text = "ใช้เวอร์ชันล่าสุดอยู่แล้ว (v${BuildConfig.VERSION_NAME})"
                    } else {
                        tvUpdateStatus.text = "มีอัปเดตใหม่พร้อมติดตั้ง"
                        pendingUpdateUrl = apkUrl
                        prefs.edit().putString("pendingReleaseTime", publishedAt).apply()
                        btnDownloadUpdate.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { tvUpdateStatus.text = "ตรวจสอบไม่สำเร็จ: ${e.message}" }
            }
        }
    }

    // Downloads the APK via Android's DownloadManager (handles retries and
    // shows a system notification), then opens the system installer once
    // the download finishes.
    private fun downloadAndInstall(apkUrl: String) {
        tvUpdateStatus.text = "กำลังดาวน์โหลด..."
        btnDownloadUpdate.isEnabled = false

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val request = android.app.DownloadManager.Request(android.net.Uri.parse(apkUrl))
            .setTitle("Wave K-Line Reader — อัปเดต")
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_DOWNLOADS, "update.apk")

        pendingDownloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != pendingDownloadId) return
                try { unregisterReceiver(this) } catch (e: Exception) { /* ignore */ }

                val file = java.io.File(
                    getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "update.apk"
                )
                if (!file.exists()) {
                    tvUpdateStatus.text = "ดาวน์โหลดไม่สำเร็จ"
                    return
                }

                val apkUri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity, "$packageName.fileprovider", file
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                tvUpdateStatus.text = "ดาวน์โหลดเสร็จแล้ว เปิดหน้าติดตั้ง..."
                prefs.edit()
                    .putString("lastSeenReleaseTime", prefs.getString("pendingReleaseTime", ""))
                    .apply()
                startActivity(installIntent)
            }
        }
        val filter = IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onComplete, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(onComplete, filter)
        }
    }

    private fun log(msg: String) {
        mainHandler.post {
            tvLog.append("\n$msg")
            scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
