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

        btnConnect.setOnClickListener { connect() }
        btnRead.setOnClickListener { readEcu() }
        btnDisconnect.setOnClickListener { disconnect() }

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

    // Best-effort parse based on community-documented byte offsets.
    // Confirm against the hex log above and adjust offsets if the values
    // don't look right for this ECU.
    private fun parseAndDisplay(bytes: ByteArray) {
        if (bytes.size < 10) {
            log("ได้รับข้อมูลแต่สั้นเกินไปที่จะตีความ ดู log ด้านบนประกอบ")
            return
        }
        val rpm = ((bytes[8].toInt() and 0xFF) shl 8) or (bytes[9].toInt() and 0xFF)
        val tps = bytes[6].toInt() and 0xFF
        mainHandler.post {
            tvRpm.text = if (rpm in 1..19999) rpm.toString() else "--"
            tvTps.text = tps.toString()
        }
    }

    private fun log(msg: String) {
        mainHandler.post {
            tvLog.append("\n$msg")
            scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
