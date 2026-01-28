package com.example.goldscalepro

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private lateinit var weightDisplay: TextView
    private lateinit var statusText: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnTare: Button

    private var usbSerialPort: UsbSerialPort? = null
    private var usbIoManager: SerialInputOutputManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // BUFFER: To hold broken pieces of data until a full line arrives
    private val dataBuffer = StringBuilder()

    private val ACTION_USB_PERMISSION = "com.example.goldscalepro.USB_PERMISSION"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        weightDisplay = findViewById(R.id.tvWeight)
        statusText = findViewById(R.id.tvStatus)
        btnConnect = findViewById(R.id.btnConnect)
        btnTare = findViewById(R.id.btnTare)

        btnConnect.setOnClickListener { connectToDevice() }

        btnTare.setOnClickListener {
            sendData("t\n")
            Toast.makeText(this, "Sent Tare Command", Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectToDevice() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // 1. Universal Driver Search
        val customTable = ProbeTable()
        customTable.addProduct(0x0000, 0x0000, CdcAcmSerialDriver::class.java)
        val customProber = UsbSerialProber(customTable)

        var availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            availableDrivers = customProber.findAllDrivers(usbManager)
        }

        if (availableDrivers.isEmpty()) {
            statusText.text = "Status: No USB Cable Detected"
            return
        }

        val driver = availableDrivers[0]
        val connection = usbManager.openDevice(driver.device)

        if (connection == null) {
            statusText.text = "Status: Permission Needed"
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(driver.device, permissionIntent)
            return
        }

        usbSerialPort = driver.ports[0]
        try {
            usbSerialPort?.open(connection)

            // --- SETTINGS ---
            usbSerialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            // --- IMPORTANT FIX: Keep data flowing ---
            usbSerialPort?.dtr = true
            usbSerialPort?.rts = true

            usbIoManager = SerialInputOutputManager(usbSerialPort, this)
            usbIoManager?.start()

            statusText.text = "Status: Connected (Stable)"
            statusText.setTextColor(android.graphics.Color.GREEN)
            btnConnect.isEnabled = false
        } catch (e: IOException) {
            statusText.text = "Status: Connection Failed"
        }
    }

    override fun onNewData(data: ByteArray) {
        mainHandler.post {
            try {
                // 1. Add new data to the buffer
                val incomingText = String(data, StandardCharsets.UTF_8)
                dataBuffer.append(incomingText)

                // 2. Check if we have a full line (ending with \n)
                // We keep processing until the buffer is empty of full lines
                while (dataBuffer.contains("\n")) {
                    val newlineIndex = dataBuffer.indexOf("\n")

                    // Extract the full line
                    val fullLine = dataBuffer.substring(0, newlineIndex).trim()

                    // Remove processed line from buffer
                    dataBuffer.delete(0, newlineIndex + 1)

                    // 3. Process the complete line
                    if (fullLine.isNotEmpty()) {
                        processWeightData(fullLine)
                    }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    private fun processWeightData(line: String) {
        // Regex: Looks for "g =" followed by a number
        val matcher = Pattern.compile("g\\s*=\\s*([-0-9\\.]+)").matcher(line)

        if (matcher.find()) {
            val weightStr = matcher.group(1)
            val rawWeight = weightStr?.toDoubleOrNull()

            if (rawWeight != null) {
                weightDisplay.text = String.format("%.2f", rawWeight)
            }
        }
    }

    private fun sendData(str: String) {
        try {
            usbSerialPort?.write(str.toByteArray(), 1000)
        } catch (e: Exception) {
            Toast.makeText(this, "Send Failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRunError(e: Exception) {
        mainHandler.post {
            statusText.text = "Status: Link Lost"
            btnConnect.isEnabled = true
        }
    }
}