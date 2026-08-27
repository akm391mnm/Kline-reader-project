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
import android.view.WindowManager
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
    // Separate executor for the retry-read work. `executor` above is
    // permanently occupied running the SerialInputOutputManager's read loop
    // (submitted once and never returns), so anything else submitted to it
    // would queue forever and never actually run.
    private val retryExecutor = Executors.newSingleThreadExecutor()
    // Real-time mode's own executor. readEcu()'s wake+init handshake does
    // several Thread.sleep() calls (up to ~490ms total) — running those on
    // the UI thread (as realtimeRunnable originally did via mainHandler)
    // freezes the whole app for that long every time a cycle has to
    // re-wake, which happens on every dropped response. On a noisy K-Line
    // connection that can mean the UI is blocked almost continuously,
    // which is what made the screen unscrollable and the stop button
    // unresponsive. Running readEcu() here instead keeps the UI thread
    // free at all times; only the actual TextView updates get posted back
    // to mainHandler.
    private val realtimeExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var tvAdapterStatus: TextView
    private lateinit var tvEcuStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var tvRpm: TextView
    private lateinit var tvTps: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnRead: Button
    private lateinit var etRetryCount: EditText
    private lateinit var btnReadRetry: Button
    private lateinit var btnCopyLog: Button
    private lateinit var btnClearLog: Button
    private lateinit var btnScanTables: Button
    private lateinit var btnDisconnect: Button
    private lateinit var etRealtimeInterval: EditText
    private lateinit var btnRealtime: Button
    private var isRealtimeRunning = false

    // Tracks whether the ECU is believed to still be in an awake/inited
    // K-Line session. When true, readEcu() can skip the wake pulse +
    // init handshake and just send the table request — this is what
    // makes real-time polling fast. Reset to false whenever we know (or
    // suspect) the session is gone: on connect/disconnect, when real-time
    // mode starts fresh, and whenever a read gets no response at all
    // (empty rxBuffer), since that usually means the ECU dropped out.
    private var kLineAwake = false

    // Recurring loop for real-time mode: runs one readEcu() cycle, then
    // reschedules itself after the configured interval. Chained via
    // mainHandler.postDelayed (same thread readEcu() already runs on)
    // rather than a background executor loop, since readEcu() itself does
    // its wake/init/request writes synchronously with short Thread.sleep
    // calls — matches the existing single-shot "ลองอ่าน ECU" behavior,
    // just repeated. isRealtimeRunning is checked on each tick so stopping
    // takes effect on the next scheduled run even if removeCallbacks
    // somehow missed the pending one.
    private val realtimeRunnable = object : Runnable {
        override fun run() {
            if (!isRealtimeRunning) return
            // forceWake=false lets readEcu() skip the wake pulse + init
            // handshake once kLineAwake is already true (set after the
            // first successful cycle) — this is what removes the ~300ms+
            // per-cycle overhead and lets real-time mode actually run at
            // the interval configured below instead of being floored by
            // the handshake itself.
            //
            // Run readEcu() on realtimeExecutor, not this (UI) thread —
            // see the comment on realtimeExecutor above for why.
            realtimeExecutor.execute {
                readEcu(forceWake = !kLineAwake) {
                    if (!isRealtimeRunning) return@readEcu
                    val interval = etRealtimeInterval.text.toString().trim().toLongOrNull()
                        ?.coerceIn(80, 10000) ?: 150L
                    mainHandler.postDelayed(this, interval)
                }
            }
        }
    }

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
        etRetryCount = findViewById(R.id.etRetryCount)
        btnReadRetry = findViewById(R.id.btnReadRetry)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        btnClearLog = findViewById(R.id.btnClearLog)
        btnScanTables = findViewById(R.id.btnScanTables)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        etRealtimeInterval = findViewById(R.id.etRealtimeInterval)
        btnRealtime = findViewById(R.id.btnRealtime)

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
        btnReadRetry.setOnClickListener { retryReadEcu() }
        btnCopyLog.setOnClickListener { copyLogToClipboard() }
        btnClearLog.setOnClickListener {
            tvLog.text = ""
            log("ล้าง log แล้ว")
        }
        btnScanTables.setOnClickListener { scanTables() }
        btnRealtime.setOnClickListener { toggleRealtime() }
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
        btnRealtime.isEnabled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) { /* already unregistered */ }
        isRealtimeRunning = false
        mainHandler.removeCallbacks(realtimeRunnable)
        ioManager?.stop()
        try { port?.close() } catch (e: Exception) { /* ignore */ }
        retryExecutor.shutdownNow()
        realtimeExecutor.shutdownNow()
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
            btnRealtime.isEnabled = true
        } catch (e: Exception) {
            log("เปิดพอร์ตไม่สำเร็จ: ${e.message}")
            tvAdapterStatus.text = "เปิดพอร์ตไม่สำเร็จ"
        }
    }

    private fun disconnect() {
        stopRealtime()
        ioManager?.stop()
        ioManager = null
        try { port?.close() } catch (e: Exception) { /* ignore */ }
        port = null
        kLineAwake = false
        tvAdapterStatus.text = "ยังไม่ได้เชื่อมต่อสาย"
        tvEcuStatus.text = "ยังไม่ได้ลองคุยกับ ECU"
        btnConnect.isEnabled = true
        btnRead.isEnabled = false
        btnDisconnect.isEnabled = false
        btnRealtime.isEnabled = false
        log("ตัดการเชื่อมต่อแล้ว")
    }

    // Toggles the repeating real-time read loop on/off. While running,
    // disables the other read actions (single read / retry / scan) since
    // they'd otherwise race with realtimeRunnable over rxBuffer and the
    // serial port.
    private fun toggleRealtime() {
        if (isRealtimeRunning) {
            stopRealtime()
        } else {
            if (port == null) {
                log("ยังไม่ได้เชื่อมต่อสาย — เชื่อมต่อก่อนเริ่มโหมด real-time")
                return
            }
            isRealtimeRunning = true
            kLineAwake = false // always wake+init fresh on the first cycle
            // Real-time mode can run far longer than the phone's normal
            // screen-timeout, and the readings view is exactly what the
            // user is watching during that time — so keep the screen on
            // for the duration instead of letting it sleep mid-session.
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            btnRealtime.text = "หยุดอ่านแบบ Real-time"
            btnRead.isEnabled = false
            btnReadRetry.isEnabled = false
            btnScanTables.isEnabled = false
            log("══ เริ่มโหมด real-time ══")
            mainHandler.post(realtimeRunnable)
        }
    }

    private fun stopRealtime() {
        if (!isRealtimeRunning) return
        isRealtimeRunning = false
        kLineAwake = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mainHandler.removeCallbacks(realtimeRunnable)
        btnRealtime.text = "เริ่มอ่านแบบ Real-time"
        btnRead.isEnabled = port != null
        btnReadRetry.isEnabled = port != null
        btnScanTables.isEnabled = port != null
        log("══ หยุดโหมด real-time ══")
    }

    // Polls rxBuffer every 100ms for a complete table 0x17 response instead
    // of always waiting the full 1400ms — most reads finish well under
    // that, so real-time mode can move on to the next cycle much sooner.
    // 1400ms remains the ceiling (same as before) so a genuinely slow/no
    // reply still gets the same grace period it always did.
    private fun pollForResponse(elapsedMs: Long, onComplete: (() -> Unit)? = null) {
        if (hasCompleteTable17Response()) {
            mainHandler.post { tvEcuStatus.text = "ECU ตอบสนอง (${rxBuffer.size} bytes รวม)" }
            parseAndDisplay(rxBuffer.toByteArray())
            onComplete?.invoke()
            return
        }
        if (elapsedMs >= 1400L) {
            if (rxBuffer.isEmpty()) {
                mainHandler.post { tvEcuStatus.text = "ไม่มีการตอบกลับจาก ECU เลย — เช็คขั้ว K-Line และสถานะกุญแจ" }
                // No reply at all usually means the K-Line session dropped
                // (e.g. gap between cycles was too long, or a real
                // disconnect) — force the next cycle to re-wake instead of
                // sending a bare request into a dead line.
                kLineAwake = false
            } else {
                mainHandler.post { tvEcuStatus.text = "ECU ตอบสนอง (${rxBuffer.size} bytes รวม)" }
                parseAndDisplay(rxBuffer.toByteArray())
            }
            onComplete?.invoke()
            return
        }
        mainHandler.postDelayed({ pollForResponse(elapsedMs + 100L, onComplete) }, 100L)
    }

    // forceWake=true (default, and what every existing caller other than
    // real-time mode should keep using) always does the full wake pulse +
    // init handshake before requesting data — safest, but each cycle pays
    // ~300-350ms for the break signal + handshake round-trips.
    //
    // forceWake=false skips straight to the table 0x17 request, reusing
    // the session from a previous cycle. The K-Line/KWP-style session on
    // this ECU stays alive as long as requests keep coming without too
    // long a gap, so real-time mode only needs to actually wake+init once
    // and can then just poll — this is what lets the interval below drop
    // into the 100-150ms range instead of being floored by the handshake.
    private fun readEcu(forceWake: Boolean = true, onComplete: (() -> Unit)? = null) {
        val p = port ?: return
        rxBuffer.clear()
        try {
            if (forceWake || !kLineAwake) {
                mainHandler.post { tvEcuStatus.text = "กำลังปลุก ECU..." }
                // Step 1: electrical wake pulse — hold K-Line low 70ms, then
                // release high 120ms. This is NOT a normal UART byte; it's a
                // physical-level signal the ECU watches for before it will
                // accept any serial data at all. Without this the ECU stays
                // asleep and anything sent afterward is just ignored/echoed.
                try {
                    p.setBreak(true)
                    Thread.sleep(70)
                    p.setBreak(false)
                    Thread.sleep(120)
                } catch (e: Exception) {
                    log("อุปกรณ์นี้อาจไม่รองรับ setBreak() โดยตรง: ${e.message} (ลองต่อไปตามปกติ)")
                }

                // Step 2: wake-up code — ECU should answer 0E 04 72 7C
                writeBytes(byteArrayOf(0xFE.toByte(), 0x04, 0x72, 0x8C.toByte()))
                Thread.sleep(150)

                // Step 3: init code — ECU should answer 02 04 00 FA
                writeBytes(byteArrayOf(0x72, 0x05, 0x00, 0xF0.toByte(), 0x99.toByte()))
                Thread.sleep(150)

                kLineAwake = true
            } else {
                mainHandler.post { tvEcuStatus.text = "กำลังอ่าน..." }
            }

            // Request data table 0x17 — confirmed via full 0x00-0x20 scan to
            // be the real live-sensor block on this ECU (table 0x11,
            // documented for other Honda K-Line ECUs, returns only a short
            // ACK on this particular unit).
            writeBytes(appendChecksum(byteArrayOf(0x72, 0x05, 0x71, 0x17)))

            pollForResponse(0L, onComplete)
        } catch (e: Exception) {
            log("เกิดข้อผิดพลาดระหว่างอ่าน ECU: ${e.message}")
            kLineAwake = false
            onComplete?.invoke()
        }
    }

    // Repeats the full wake/init/request handshake up to `count` times on a
    // background thread (so the UI doesn't freeze across multiple ~1.5s
    // attempts), stopping early if a response comes back long enough to be
    // real sensor data rather than just the 5-byte ACK echo. Every attempt
    // logs its own byte count so a partial success is still visible even if
    // none of them reach the "success" threshold.
    private fun copyLogToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = tvLog.text.toString()
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Wave K-Line log", text))
        log("คัดลอก log ทั้งหมดแล้ว (${text.length} ตัวอักษร) — วางได้เลย")
    }

    // Tries every table ID from 0x00 to 0x20 against the ECU, one at a time,
    // and logs how many bytes came back for each. A table the ECU doesn't
    // support typically returns just the short ACK echo (a handful of
    // bytes); a table with real sensor data comes back noticeably longer.
    // Runs on retryExecutor since `executor` is permanently busy with the
    // continuous USB read loop.
    private fun scanTables() {
        val p = port ?: return
        btnScanTables.isEnabled = false
        btnReadRetry.isEnabled = false
        btnRead.isEnabled = false
        tvEcuStatus.text = "กำลังสแกนหา table (0x00–0x20)..."
        log("══ เริ่มสแกน table 0x00–0x20 ══")

        retryExecutor.submit {
            val results = mutableListOf<Pair<Int, Int>>()
            for (tableId in 0x00..0x20) {
                rxBuffer.clear()
                try {
                    try {
                        p.setBreak(true)
                        Thread.sleep(70)
                        p.setBreak(false)
                        Thread.sleep(120)
                    } catch (e: Exception) { /* device may not support setBreak */ }

                    writeBytes(byteArrayOf(0xFE.toByte(), 0x04, 0x72, 0x8C.toByte()))
                    Thread.sleep(150)
                    writeBytes(byteArrayOf(0x72, 0x05, 0x00, 0xF0.toByte(), 0x99.toByte()))
                    Thread.sleep(150)
                    writeBytes(appendChecksum(byteArrayOf(0x72, 0x05, 0x71, tableId.toByte())))
                    Thread.sleep(800)

                    val size = rxBuffer.size
                    results.add(tableId to size)
                    mainHandler.post { log("table 0x%02X → %d bytes".format(tableId, size)) }
                } catch (e: Exception) {
                    mainHandler.post { log("table 0x%02X error: ${e.message}".format(tableId)) }
                }
            }

            mainHandler.post {
                btnScanTables.isEnabled = true
                btnReadRetry.isEnabled = true
                btnRead.isEnabled = true
                val sorted = results.sortedByDescending { it.second }
                val best = sorted.firstOrNull()
                if (best != null && best.second > 0) {
                    tvEcuStatus.text = "สแกนเสร็จ — table ที่ยาวสุด: 0x%02X (%d bytes)".format(best.first, best.second)
                    log("══ สรุปผล 5 อันดับที่ยาวที่สุด ══")
                    sorted.take(5).forEach { log("table 0x%02X: %d bytes".format(it.first, it.second)) }
                } else {
                    tvEcuStatus.text = "สแกนเสร็จ แต่ไม่พบ table ที่ตอบกลับเลย"
                }
            }
        }
    }

    private fun retryReadEcu() {
        val p = port ?: return
        val count = etRetryCount.text.toString().trim().toIntOrNull()?.coerceIn(1, 20) ?: 5
        btnReadRetry.isEnabled = false
        btnRead.isEnabled = false
        tvEcuStatus.text = "กำลังลองอ่านซ้ำ (สูงสุด $count ครั้ง)..."

        retryExecutor.submit {
            var success = false
            for (attempt in 1..count) {
                if (success) break
                mainHandler.post { log("── รอบที่ $attempt/$count ──") }
                rxBuffer.clear()
                try {
                    try {
                        p.setBreak(true)
                        Thread.sleep(70)
                        p.setBreak(false)
                        Thread.sleep(120)
                    } catch (e: Exception) {
                        mainHandler.post { log("setBreak ไม่รองรับ: ${e.message}") }
                    }

                    writeBytes(byteArrayOf(0xFE.toByte(), 0x04, 0x72, 0x8C.toByte()))
                    Thread.sleep(150)
                    writeBytes(byteArrayOf(0x72, 0x05, 0x00, 0xF0.toByte(), 0x99.toByte()))
                    Thread.sleep(150)
                    writeBytes(appendChecksum(byteArrayOf(0x72, 0x05, 0x71, 0x17)))
                    Thread.sleep(1200)

                    val size = rxBuffer.size
                    mainHandler.post { log("รอบที่ $attempt ได้ข้อมูล $size bytes รวม") }

                    // Heuristic: the wake+init ACKs alone add up to well under
                    // 20 bytes, so anything at or above that likely includes
                    // the real table-11 payload rather than just the ACK echo.
                    // Baseline (ACK-only, no real data) is consistently 28
                    // bytes total across every table ID we scanned; table
                    // 0x17's real payload brings that up to ~47 bytes.
                    if (size >= 35) {
                        success = true
                        val snapshot = rxBuffer.toByteArray()
                        mainHandler.post {
                            tvEcuStatus.text = "ECU ตอบสนองแบบเต็ม (รอบที่ $attempt, $size bytes)"
                            parseAndDisplay(snapshot)
                        }
                    }
                } catch (e: Exception) {
                    mainHandler.post { log("รอบที่ $attempt error: ${e.message}") }
                }
            }

            mainHandler.post {
                btnReadRetry.isEnabled = true
                btnRead.isEnabled = true
                if (!success) {
                    tvEcuStatus.text = "ลองครบ $count รอบแล้ว ยังไม่ได้ข้อมูลเต็ม — ดู log แต่ละรอบด้านล่าง"
                    if (rxBuffer.isNotEmpty()) parseAndDisplay(rxBuffer.toByteArray())
                }
            }
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

    // Checks whether a full table 0x17 response has already arrived in
    // rxBuffer, so readEcu() can stop waiting as soon as data is ready
    // instead of always sitting out the full timeout. The confirmed
    // table 0x17 frame is marker(02 LEN 71 17) + 19 payload bytes +
    // 1 checksum byte = 24 bytes from the marker onward.
    private fun hasCompleteTable17Response(): Boolean {
        val bytes = rxBuffer.toByteArray()
        for (i in 0..bytes.size - 4) {
            if ((bytes[i].toInt() and 0xFF) == 0x02 &&
                (bytes[i + 2].toInt() and 0xFF) == 0x71 &&
                (bytes[i + 3].toInt() and 0xFF) == 0x17
            ) {
                return bytes.size - i >= 24
            }
        }
        return false
    }

    // Searches the raw captured bytes for the response-frame marker
    // [0x02, LEN, 0x71, tableId] and returns just the data payload that
    // follows it (dropping the trailing checksum byte), ignoring whatever
    // noise/ACK bytes came before it in the buffer. This makes parsing
    // robust regardless of how much filler precedes the real response —
    // which varies from read to read and was the root cause of offsets
    // never lining up reliably when we parsed the raw buffer directly.
    private fun extractTablePayload(bytes: ByteArray, tableId: Int): ByteArray? {
        for (i in 0..bytes.size - 4) {
            if ((bytes[i].toInt() and 0xFF) == 0x02 &&
                (bytes[i + 2].toInt() and 0xFF) == 0x71 &&
                (bytes[i + 3].toInt() and 0xFF) == tableId
            ) {
                val dataStart = i + 4
                val dataEnd = bytes.size - 1 // last byte assumed checksum
                if (dataEnd <= dataStart) return null
                return bytes.copyOfRange(dataStart, dataEnd)
            }
        }
        return null
    }

    // Uses the configurable offsets set in the "ตั้งค่า offset" section so
    // values can be corrected live from the auto-diff results without
    // rebuilding the app. Offsets are relative to the extracted table 0x17
    // payload, not the raw buffer.
    private fun parseAndDisplay(bytes: ByteArray) {
        val payload = extractTablePayload(bytes, 0x17)
        if (payload == null) {
            log("หา table 0x17 response ในข้อมูลที่ได้ไม่เจอ — อาจเป็นแค่ ACK ดู log ประกอบ")
            return
        }
        log("พบ payload table 0x17: ${payload.size} bytes — ${payload.joinToString(" ") { "%02X".format(it) }}")

        fun byteAt(offset: Int): Int? =
            if (offset in payload.indices) payload[offset].toInt() and 0xFF else null

        val rpmHi = byteAt(offRpm)
        val rpmLo = byteAt(offRpm + 1)
        val rpm = if (rpmHi != null && rpmLo != null) (rpmHi shl 8) or rpmLo else null

        // TPS is a 2-byte big-endian raw value at offTps/offTps+1, not a
        // single byte — confirmed via calibration samples: throttle fully
        // released reads ~0x1900 (6400 dec), fully open reads ~0xD996-0xDA96
        // (~55700-55960 dec). Converted to a 0-100% range below; the raw
        // bounds are approximate from 2 calibration points (released/full)
        // so this may need a small tweak once a mid-throttle sample is in.
        val tpsHi = byteAt(offTps)
        val tpsLo = byteAt(offTps + 1)
        val tpsRaw = if (tpsHi != null && tpsLo != null) (tpsHi shl 8) or tpsLo else null
        val tps = tpsRaw?.let {
            // maxRaw is the measured raw value at true full throttle,
            // confirmed twice now across separate sessions (0xD996/0xDA96,
            // ~55700-55958 dec — consistent). An earlier attempt lowered
            // this to 43473 based on a report of "only 75% at full
            // throttle", but that was a misdiagnosis: the real cause was
            // the UI-freeze bug during real-time mode, not a wrong raw
            // range. Reverted to the directly-measured value.
            val minRaw = 6400
            val maxRaw = 55958
            (((it - minRaw) * 100) / (maxRaw - minRaw)).coerceIn(0, 100)
        }
        val ect = byteAt(offEct)
        val iat = byteAt(offIat)
        val af = byteAt(offAf)
        val lightByte = byteAt(offLightByte)
        val lightOn = lightByte != null && offLightBit in 0..7 &&
            (lightByte and (1 shl offLightBit)) != 0

        mainHandler.post {
            tvRpm.text = if (rpm != null && rpm in 1..19999) rpm.toString() else "--"
            tvTps.text = tps?.let { "$it%" } ?: "--"
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
    // offset corresponds to which sensor value. Saves the extracted table
    // 0x17 payload (not the raw buffer) so noise-length variation between
    // reads can't create false differences during analysis.
    private fun saveSample() {
        if (rxBuffer.isEmpty()) {
            log("ยังไม่มีข้อมูลจากการอ่าน ECU ล่าสุด กด \"ลองอ่าน ECU\" ก่อน")
            return
        }
        val payload = extractTablePayload(rxBuffer.toByteArray(), 0x17)
        if (payload == null) {
            log("หา table 0x17 payload ไม่เจอในข้อมูลล่าสุด — บันทึกไม่ได้ ลองอ่านใหม่อีกครั้ง")
            return
        }
        val label = etLabel.text.toString().trim().ifEmpty { "ตัวอย่าง ${samples.size + 1}" }
        samples.add(Sample(label, payload))
        log("บันทึกตัวอย่าง \"$label\" แล้ว (${payload.size} bytes) — มีทั้งหมด ${samples.size} ตัวอย่าง")
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
                val url = java.net.URL("https://api.github.com/repos/akm391mnm/Kline-reader-project/releases/tags/latest")
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
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                var apkUpdatedAt: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url")
                            // Unlike the release's published_at (which is set once
                            // and never changes), the asset's updated_at DOES change
                            // every time a new APK is uploaded to the same release —
                            // this is what actually tells us a new build exists.
                            apkUpdatedAt = asset.optString("updated_at")
                            break
                        }
                    }
                }

                mainHandler.post {
                    if (apkUrl == null) {
                        tvUpdateStatus.text = "ไม่พบไฟล์ APK ใน release ล่าสุด"
                        return@post
                    }
                    val lastSeen = prefs.getString("lastSeenReleaseTime", "")
                    if (!apkUpdatedAt.isNullOrEmpty() && apkUpdatedAt == lastSeen) {
                        tvUpdateStatus.text = "ใช้เวอร์ชันล่าสุดอยู่แล้ว (v${BuildConfig.VERSION_NAME})"
                    } else {
                        tvUpdateStatus.text = "มีอัปเดตใหม่พร้อมติดตั้ง"
                        pendingUpdateUrl = apkUrl
                        prefs.edit().putString("pendingReleaseTime", apkUpdatedAt ?: "").apply()
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

        // Delete any leftover APK from a previous update attempt first —
        // otherwise a failed/blocked download can leave the OLD file sitting
        // there, and the completion check below would mistake its mere
        // existence for a successful fresh download.
        val targetFile = java.io.File(
            getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
            "update.apk"
        )
        if (targetFile.exists()) targetFile.delete()

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

                // Verify the download actually succeeded rather than just
                // checking file existence — a failed download can still
                // leave a partial or stale file behind.
                val query = android.app.DownloadManager.Query().setFilterById(id)
                val cursor = downloadManager.query(query)
                var statusOk = false
                if (cursor != null && cursor.moveToFirst()) {
                    val statusIdx = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)
                    val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
                    statusOk = status == android.app.DownloadManager.STATUS_SUCCESSFUL
                    if (!statusOk) {
                        val reasonIdx = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_REASON)
                        val reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else -1
                        log("ดาวน์โหลดไม่สำเร็จ: status=$status reason=$reason")
                    }
                    cursor.close()
                }
                if (!statusOk) {
                    tvUpdateStatus.text = "ดาวน์โหลดไม่สำเร็จ ลองกดใหม่อีกครั้ง"
                    btnDownloadUpdate.isEnabled = true
                    return
                }

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
            // Only auto-scroll if the user was already at (or very near)
            // the bottom before this line was added. Otherwise appending
            // would yank them back down while they're reading earlier
            // lines — check BEFORE appending, since appending changes the
            // scroll range.
            val wasNearBottom = !scrollLog.canScrollVertically(1)
            tvLog.append("\n$msg")
            if (wasNearBottom) {
                scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }
}
