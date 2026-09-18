package com.example.signlanguage

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 主界面：通过 HC-05 蓝牙串口（SPP）与 STM32 通信。
 *
 * 职责划分：
 * - 连接 / 读取在后台 [Thread] 中完成，界面更新统一 post 到主线程 [Handler]
 * - 接收到的字节流按 '\n' 分行、去掉 '\r'，再按 [Key]Value 协议解析
 * - 识别的文字可用手机内置 TTS 朗读（可开关）
 *
 * 不依赖协程、Room、Compose、ViewBinding 以及任何第三方库。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SignLanguage"

        /** 权限申请请求码 */
        private const val REQ_BLUETOOTH = 1001

        /** HC-05 使用的 SPP（串口）服务 UUID，所有蓝牙串口模块都是这个固定值 */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** 历史记录最多保留条数 */
        private const val MAX_HISTORY = 50

        /** 心率折线图最多保留采样点 */
        private const val MAX_HR_SAMPLES = 60

        /** 未收到换行时的缓冲上限，防止内存无限增长 */
        private const val MAX_BUFFER_BYTES = 4096

        /** TTS 朗读任务标识 */
        private const val UTTERANCE_ID = "sign-language-utterance"

        /** 默认音量（0 ~ 30） */
        private const val DEFAULT_VOLUME = 20
    }

    // ==================== 界面控件 ====================
    private lateinit var tvStatusChip: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnRetry: Button
    private lateinit var scrollRoot: ScrollView
    private lateinit var tvGestureText: TextView
    private lateinit var tvGestureCode: TextView
    private lateinit var tvGestureTime: TextView
    private lateinit var tvHeartRate: TextView
    private lateinit var tvSpo2: TextView
    private lateinit var sparkline: SparklineView
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var layoutHistory: LinearLayout
    private lateinit var switchSpeak: SwitchCompat
    private lateinit var tvVolume: TextView
    private lateinit var seekVolume: SeekBar
    private lateinit var btnClear: Button
    private lateinit var btnReplay: Button
    private lateinit var btnPing: Button

    // ==================== 蓝牙状态 ====================
    private var adapter: BluetoothAdapter? = null

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var outStream: OutputStream? = null

    @Volatile
    private var connected = false

    /**
     * 连接代号：每次新建连接自增。
     * 后台线程在读写前后都会比对代号，代号变了说明该线程属于旧的连接，必须立即退出，
     * 避免旧线程把新连接的状态改坏。
     */
    @Volatile
    private var linkGeneration = 0

    private var connectThread: Thread? = null
    private var readThread: Thread? = null
    private var lastDevice: BluetoothDevice? = null
    private var lastDeviceName: String = ""

    // ==================== 数据状态 ====================
    /** 主线程 Handler：所有界面更新都通过它 post */
    private val uiHandler = Handler(Looper.getMainLooper())

    /** 时间格式化（只在主线程使用） */
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 当前这条识别记录 */
    private var curCode = ""
    private var curText = ""
    private var curTime = ""

    /** 心率采样（用于折线图） */
    private val hrSamples = ArrayList<Int>()

    private var lastHr = 0
    private var lastSpo2 = 0

    // ==================== TTS ====================
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakEnabled = false

    // ================================================================
    //  生命周期
    // ================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        initBluetooth()
        initTts()
        setupListeners()

        updateStatusUi()
        renderCurrent()
        renderMetrics()
        tvVolume.text = getString(R.string.volume_fmt, seekVolume.progress)
        setHint(getString(R.string.status_disconnected))
    }

    override fun onDestroy() {
        super.onDestroy()

        // 先断开（内部会关闭 socket，让后台线程的阻塞读写立刻抛异常退出）
        disconnect(false)
        // 丢弃还没执行的界面更新任务，避免泄漏 Activity
        uiHandler.removeCallbacksAndMessages(null)

        val engine = tts
        if (engine != null) {
            try {
                engine.stop()
                engine.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "关闭 TTS 失败", e)
            }
        }
        tts = null
        ttsReady = false
    }

    // ================================================================
    //  初始化
    // ================================================================

    private fun bindViews() {
        tvStatusChip = findViewById(R.id.tvStatusChip)
        tvHint = findViewById(R.id.tvHint)
        btnConnect = findViewById(R.id.btnConnect)
        btnRetry = findViewById(R.id.btnRetry)
        scrollRoot = findViewById(R.id.scrollRoot)
        tvGestureText = findViewById(R.id.tvGestureText)
        tvGestureCode = findViewById(R.id.tvGestureCode)
        tvGestureTime = findViewById(R.id.tvGestureTime)
        tvHeartRate = findViewById(R.id.tvHeartRate)
        tvSpo2 = findViewById(R.id.tvSpo2)
        sparkline = findViewById(R.id.sparkline)
        tvHistoryEmpty = findViewById(R.id.tvHistoryEmpty)
        layoutHistory = findViewById(R.id.layoutHistory)
        switchSpeak = findViewById(R.id.switchSpeak)
        tvVolume = findViewById(R.id.tvVolume)
        seekVolume = findViewById(R.id.seekVolume)
        btnClear = findViewById(R.id.btnClear)
        btnReplay = findViewById(R.id.btnReplay)
        btnPing = findViewById(R.id.btnPing)
    }

    /**
     * 获取蓝牙适配器；没有蓝牙的设备上给出提示而不是崩溃。
     */
    private fun initBluetooth() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = manager?.adapter

        if (adapter == null) {
            setHint(getString(R.string.hint_bt_unsupported))
            btnConnect.isEnabled = false
            btnRetry.isEnabled = false
        }
    }

    /**
     * 初始化本地 TTS（中文）。
     */
    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            var ok = false
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine != null) {
                    val result = engine.setLanguage(Locale.CHINESE)
                    ok = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                }
            }
            ttsReady = ok
            if (!ok) {
                Log.w(TAG, "TTS 中文引擎不可用，手机朗读将自动跳过")
            }
        }
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener { onConnectClicked() }
        btnRetry.setOnClickListener { onRetryClicked() }
        btnClear.setOnClickListener { onClearClicked() }
        btnReplay.setOnClickListener { sendCommand("REPLAY") }
        btnPing.setOnClickListener { sendCommand("PING") }

        switchSpeak.setOnCheckedChangeListener { _, isChecked ->
            speakEnabled = isChecked
        }

        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVolume.text = getString(R.string.volume_fmt, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // 不需要处理
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // 松手时才发送，避免拖动过程中刷屏
                val value = seekBar?.progress ?: return
                sendCommand("VOL:" + value)
            }
        })

        seekVolume.progress = DEFAULT_VOLUME
    }

    // ================================================================
    //  权限
    // ================================================================

    /**
     * 按系统版本返回需要申请的权限：
     * - Android 12（API 31）及以上：BLUETOOTH_CONNECT + BLUETOOTH_SCAN
     * - Android 6 ~ 11：ACCESS_FINE_LOCATION（扫描/读取已配对设备所必需）
     * - Android 6 以下：安装即授予，无需申请
     */
    private fun requiredPermissions(): Array<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return arrayOf()
    }

    private fun hasAllPermissions(): Boolean {
        val permissions = requiredPermissions()
        var i = 0
        while (i < permissions.size) {
            if (ContextCompat.checkSelfPermission(this, permissions[i]) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            i++
        }
        return true
    }

    /**
     * 权限齐全返回 true；否则发起申请并返回 false（结果在 onRequestPermissionsResult 里处理）。
     */
    private fun ensurePermissions(): Boolean {
        val missing = ArrayList<String>()
        val permissions = requiredPermissions()
        var i = 0
        while (i < permissions.size) {
            if (ContextCompat.checkSelfPermission(this, permissions[i]) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                missing.add(permissions[i])
            }
            i++
        }
        if (missing.isEmpty()) return true

        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_BLUETOOTH)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BLUETOOTH) return

        if (hasAllPermissions()) {
            setHint(getString(R.string.hint_permission_ok))
            showDeviceDialog()
        } else {
            setHint(getString(R.string.hint_permission_denied))
            toast(getString(R.string.hint_permission_denied))
        }
    }

    // ================================================================
    //  选择设备 / 连接 / 断开
    // ================================================================

    private fun onConnectClicked() {
        if (connected) {
            disconnect(true)
            return
        }
        if (!ensurePermissions()) return
        showDeviceDialog()
    }

    private fun onRetryClicked() {
        if (!ensurePermissions()) return
        val device = lastDevice
        if (device == null) {
            showDeviceDialog()
        } else {
            connectTo(device)
        }
    }

    /**
     * 弹出已配对设备列表（简单 AlertDialog，不需要 RecyclerView）。
     */
    @SuppressLint("MissingPermission")
    private fun showDeviceDialog() {
        val bt = adapter
        if (bt == null) {
            setHint(getString(R.string.hint_bt_unsupported))
            return
        }

        val enabled = try {
            bt.isEnabled
        } catch (e: SecurityException) {
            Log.w(TAG, "读取蓝牙开关状态失败", e)
            false
        }
        if (!enabled) {
            setHint(getString(R.string.hint_bt_off))
            toast(getString(R.string.hint_bt_off))
            return
        }

        val devices = bondedDevices(bt)
        if (devices.isEmpty()) {
            setHint(getString(R.string.hint_no_paired))
            toast(getString(R.string.hint_no_paired))
            return
        }

        val names = ArrayList<String>()
        var i = 0
        while (i < devices.size) {
            val device = devices[i]
            val name = safeDeviceName(device)
            val address = try {
                device.address
            } catch (e: SecurityException) {
                ""
            }
            names.add(name + "\n" + address)
            i++
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_choose_device)
            .setItems(names.toTypedArray(), DialogInterface.OnClickListener { _, which ->
                if (which >= 0 && which < devices.size) {
                    connectTo(devices[which])
                }
            })
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun bondedDevices(bt: BluetoothAdapter): List<BluetoothDevice> {
        val result = ArrayList<BluetoothDevice>()
        try {
            val bonded = bt.bondedDevices
            if (bonded != null) {
                result.addAll(bonded)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "读取已配对设备失败（缺少权限）", e)
        }
        return result
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String {
        val name = try {
            device.name
        } catch (e: SecurityException) {
            null
        }
        if (!name.isNullOrEmpty()) return name

        val address = try {
            device.address
        } catch (e: SecurityException) {
            null
        }
        if (!address.isNullOrEmpty()) return address

        return getString(R.string.unknown_device)
    }

    /**
     * 在后台线程连接 HC-05。
     */
    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        // 关闭旧连接并让旧线程失效
        disconnect(false)
        linkGeneration++
        val generation = linkGeneration

        lastDevice = device
        lastDeviceName = safeDeviceName(device)

        connected = false
        tvStatusChip.text = getString(R.string.status_connecting)
        tvStatusChip.setBackgroundResource(R.drawable.bg_status_chip_off)
        tvStatusChip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        tvHint.text = getString(R.string.hint_connecting_fmt, lastDeviceName)
        btnConnect.isEnabled = false

        val thread = Thread {
            var ok = false
            try {
                // 标准 SPP 串口服务 UUID
                val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = newSocket
                try {
                    adapter?.cancelDiscovery()
                } catch (e: SecurityException) {
                    Log.w(TAG, "取消扫描失败（缺少权限）", e)
                }
                newSocket.connect()
                outStream = newSocket.outputStream
                ok = true
            } catch (e: Exception) {
                Log.w(TAG, "连接失败", e)
                ok = false
                closeSocketQuietly()
            }

            val success = ok
            uiHandler.post {
                // 代号变了说明期间用户又发起了新连接，本次结果作废
                if (generation != linkGeneration) return@post

                btnConnect.isEnabled = true
                if (success) {
                    connected = true
                    updateStatusUi()
                    setHint(getString(R.string.hint_connected_fmt, lastDeviceName))
                    startReadThread(generation)
                } else {
                    connected = false
                    updateStatusUi()
                    setHint(getString(R.string.hint_connect_failed))
                }
            }
        }
        thread.isDaemon = true
        connectThread = thread
        thread.start()
    }

    /**
     * 后台读取线程：把收到的字节按 '\n' 切分成行，再 post 到主线程解析。
     */
    private fun startReadThread(generation: Int) {
        val currentSocket = socket
        if (currentSocket == null) return

        val thread = Thread {
            var stream: InputStream? = null
            try {
                stream = currentSocket.inputStream
            } catch (e: IOException) {
                Log.w(TAG, "获取输入流失败", e)
                stream = null
            }

            if (stream == null) {
                uiHandler.post {
                    if (generation == linkGeneration) {
                        onLinkLost(getString(R.string.hint_stream_failed))
                    }
                }
            } else {
                // 用 val 保存一份非空引用，避免后面反复做空判断
                val input: InputStream = stream
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(512)

                while (generation == linkGeneration) {
                    var read = -1
                    try {
                        read = input.read(chunk)
                    } catch (e: IOException) {
                        Log.d(TAG, "读取结束（连接已关闭）", e)
                        read = -1
                    }
                    if (read <= 0) break

                    val lines = extractLines(buffer, chunk, read)
                    if (lines.isNotEmpty()) {
                        uiHandler.post {
                            if (generation == linkGeneration) {
                                var i = 0
                                while (i < lines.size) {
                                    handleLine(lines[i])
                                    i++
                                }
                            }
                        }
                    }
                }

                uiHandler.post {
                    if (generation == linkGeneration) {
                        onLinkLost(getString(R.string.hint_link_lost))
                    }
                }
            }
        }
        thread.isDaemon = true
        readThread = thread
        thread.start()
    }

    /**
     * 断开连接（可在任意线程调用，界面更新已 post 到主线程）。
     */
    private fun disconnect(showHint: Boolean) {
        // 代号自增：所有正在运行的后台线程都会因此判断出自己已过期
        linkGeneration++

        connected = false
        outStream = null
        closeSocketQuietly()

        connectThread?.interrupt()
        connectThread = null
        readThread?.interrupt()
        readThread = null

        updateStatusUi()
        if (showHint) {
            setHint(getString(R.string.hint_disconnected))
        }
    }

    private fun onLinkLost(message: String) {
        connected = false
        outStream = null
        closeSocketQuietly()
        updateStatusUi()
        setHint(message)
    }

    private fun closeSocketQuietly() {
        val current = socket
        socket = null
        if (current != null) {
            try {
                current.close()
            } catch (e: Exception) {
                Log.d(TAG, "关闭 socket 失败", e)
            }
        }
    }

    // ================================================================
    //  发送命令
    // ================================================================

    /**
     * 发送一行命令，自动补上 "\r\n"。
     */
    private fun sendCommand(cmd: String) {
        val stream = outStream
        if (!connected || stream == null) {
            setHint(getString(R.string.hint_not_connected))
            toast(getString(R.string.hint_not_connected))
            return
        }

        val generation = linkGeneration
        val payload = (cmd + "\r\n").toByteArray(Charsets.UTF_8)

        val thread = Thread {
            try {
                stream.write(payload)
                stream.flush()
                uiHandler.post {
                    if (generation == linkGeneration) {
                        setHint(getString(R.string.hint_sent_fmt, cmd))
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "发送失败", e)
                uiHandler.post {
                    if (generation == linkGeneration) {
                        setHint(getString(R.string.hint_send_failed))
                        onLinkLost(getString(R.string.hint_send_failed))
                    }
                }
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    // ================================================================
    //  协议解析
    // ================================================================

    /**
     * 把累积缓冲区 + 新到达的字节按 '\n' 切分成完整行（后台线程调用，不碰界面）。
     *
     * @return 本次切分出的完整行（已去掉 '\r' 与首尾空白，空行不返回）
     */
    private fun extractLines(acc: ByteArrayOutputStream, data: ByteArray, length: Int): List<String> {
        acc.write(data, 0, length)
        val all = acc.toByteArray()
        acc.reset()

        val result = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < all.size) {
            if (all[i] == '\n'.code.toByte()) {
                val line = decodeLine(all, start, i - start)
                if (line.isNotEmpty()) {
                    result.add(line)
                }
                start = i + 1
            }
            i++
        }

        // 剩下的半行留到下次；若长期收不到换行则丢弃，避免内存持续增长
        if (start < all.size) {
            acc.write(all, start, all.size - start)
            if (acc.size() > MAX_BUFFER_BYTES) {
                acc.reset()
            }
        }
        return result
    }

    /**
     * 按 UTF-8 解码一行，并去掉行尾的 '\r' / 空白。
     * 中文在 UTF-8 下是 3 字节，必须整行一起解码，不能按字节转字符。
     */
    private fun decodeLine(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset + length
        while (end > offset) {
            val b = bytes[end - 1]
            if (b == '\r'.code.toByte() || b == '\n'.code.toByte() ||
                b == ' '.code.toByte() || b == '\t'.code.toByte()
            ) {
                end--
            } else {
                break
            }
        }
        if (end <= offset) return ""
        return String(bytes, offset, end - offset, Charsets.UTF_8).trim()
    }

    /**
     * 解析一行协议数据（主线程调用）。
     *
     * 协议格式：[Key]Value
     * [Gesture]S24 / [Text]我 / [HR]75 / [SpO2]98 / [OK]SignTranslator
     */
    private fun handleLine(line: String) {
        if (line.isEmpty()) return

        if (line.startsWith("[")) {
            val close = line.indexOf(']')
            if (close > 1) {
                val key = line.substring(1, close)
                val value = line.substring(close + 1).trim()

                when {
                    key.equals("Gesture", true) -> onGestureCode(value)
                    key.equals("Text", true) -> onGestureText(value)
                    key.equals("HR", true) -> onHeartRate(value)
                    key.equals("SpO2", true) -> onSpo2(value)
                    key.equals("OK", true) -> setHint(getString(R.string.hint_ok_fmt, value))
                    key.equals("ERR", true) -> setHint(getString(R.string.hint_err_fmt, value))
                    else -> setHint(line)
                }
                return
            }
        }

        // 不是协议行：原样显示，方便调试
        setHint(line)
    }

    /**
     * 收到 [Gesture]xxx。
     *
     * 当前记录的“代码”字段已占用时，说明上一条记录结束，先归档再开新记录。
     * 这样无论 [Gesture] 和 [Text] 谁先到达都能正确配对。
     */
    private fun onGestureCode(code: String) {
        if (code.isEmpty()) return
        if (curCode.isNotEmpty()) {
            finalizeCurrent()
        }
        curCode = code
        markEntryTime()
        renderCurrent()
    }

    /**
     * 收到 [Text]xxx（UTF-8 中文）。
     */
    private fun onGestureText(text: String) {
        if (text.isEmpty()) return
        if (curText.isNotEmpty()) {
            finalizeCurrent()
        }
        curText = text
        markEntryTime()
        renderCurrent()
        speak(text)
    }

    /**
     * 把当前记录写入历史并清空当前记录。
     */
    private fun finalizeCurrent() {
        if (curCode.isEmpty() && curText.isEmpty()) return
        addHistoryRow(curCode, curText, curTime)
        curCode = ""
        curText = ""
        curTime = ""
    }

    /**
     * 收到 [HR]75。
     */
    private fun onHeartRate(value: String) {
        val rate = value.toIntOrNull() ?: return
        if (rate <= 0) {
            // 0 或无效值视为“无数据”
            lastHr = 0
            renderMetrics()
            return
        }
        lastHr = rate
        hrSamples.add(rate)
        while (hrSamples.size > MAX_HR_SAMPLES) {
            hrSamples.removeAt(0)
        }
        sparkline.setSamples(hrSamples)
        renderMetrics()
    }

    /**
     * 收到 [SpO2]98。
     */
    private fun onSpo2(value: String) {
        val percent = value.toIntOrNull() ?: return
        lastSpo2 = if (percent <= 0) 0 else percent
        renderMetrics()
    }

    // ================================================================
    //  界面刷新
    // ================================================================

    private fun renderCurrent() {
        val hasText = curText.isNotEmpty()
        val hasCode = curCode.isNotEmpty()

        tvGestureText.text = when {
            hasText -> curText
            hasCode -> curCode
            else -> getString(R.string.gesture_placeholder)
        }
        tvGestureCode.text = if (hasCode) {
            getString(R.string.gesture_code_fmt, curCode)
        } else {
            getString(R.string.gesture_code_empty)
        }
        tvGestureTime.text = if (curTime.isEmpty()) "" else getString(R.string.gesture_time_fmt, curTime)
    }

    private fun renderMetrics() {
        tvHeartRate.text = if (lastHr > 0) {
            lastHr.toString()
        } else {
            getString(R.string.value_placeholder)
        }
        tvSpo2.text = if (lastSpo2 > 0) {
            lastSpo2.toString()
        } else {
            getString(R.string.value_placeholder)
        }
    }

    /**
     * 新记录开始：记录时间，并把界面滚回顶部方便看大字。
     */
    private fun markEntryTime() {
        if (curTime.isEmpty()) {
            curTime = timeFormat.format(Date())
            if (scrollRoot.scrollY > 0) {
                scrollRoot.smoothScrollTo(0, 0)
            }
        }
    }

    /**
     * 用代码往 LinearLayout 里加一行历史（最新的在最上面，最多 [MAX_HISTORY] 条）。
     */
    private fun addHistoryRow(code: String, text: String, time: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(10), dp(12), dp(10))
        row.background = ContextCompat.getDrawable(this, R.drawable.bg_history_row)

        val mainText = TextView(this)
        mainText.text = if (text.isNotEmpty()) text else code
        mainText.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        mainText.textSize = 16f
        mainText.maxLines = 2
        mainText.ellipsize = TextUtils.TruncateAt.END

        val metaText = TextView(this)
        metaText.text = buildMetaText(code, time)
        metaText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        metaText.textSize = 11f
        metaText.gravity = Gravity.END

        row.addView(
            mainText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            metaText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val rowParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowParams.bottomMargin = dp(6)

        // index = 0：最新的排在最前面
        layoutHistory.addView(row, 0, rowParams)

        // 超出上限就删掉最旧的一条
        while (layoutHistory.childCount > MAX_HISTORY) {
            layoutHistory.removeViewAt(layoutHistory.childCount - 1)
        }

        tvHistoryEmpty.visibility = View.GONE
    }

    /**
     * 历史行右侧的小字：手势代码 + 时间。
     */
    private fun buildMetaText(code: String, time: String): String {
        val builder = StringBuilder()
        if (code.isNotEmpty()) {
            builder.append(code)
        }
        if (time.isNotEmpty()) {
            if (builder.isNotEmpty()) {
                builder.append("  ")
            }
            builder.append(time)
        }
        return builder.toString()
    }

    private fun onClearClicked() {
        clearAll()
        if (connected) {
            sendCommand("CLEAR")
        }
    }

    /**
     * 清空本地显示（历史、当前手势、心率 / 血氧、折线图）。
     */
    private fun clearAll() {
        layoutHistory.removeAllViews()
        tvHistoryEmpty.visibility = View.VISIBLE

        curCode = ""
        curText = ""
        curTime = ""

        hrSamples.clear()
        lastHr = 0
        lastSpo2 = 0
        sparkline.clearSamples()

        renderCurrent()
        renderMetrics()

        if (scrollRoot.scrollY > 0) {
            scrollRoot.smoothScrollTo(0, 0)
        }
    }

    private fun updateStatusUi() {
        if (connected) {
            tvStatusChip.text = if (lastDeviceName.isEmpty()) {
                getString(R.string.status_connected)
            } else {
                getString(R.string.status_connected_fmt, lastDeviceName)
            }
            tvStatusChip.setBackgroundResource(R.drawable.bg_status_chip_on)
            tvStatusChip.setTextColor(ContextCompat.getColor(this, R.color.chip_on_text))
            btnConnect.text = getString(R.string.btn_disconnect)
        } else {
            tvStatusChip.text = getString(R.string.status_disconnected)
            tvStatusChip.setBackgroundResource(R.drawable.bg_status_chip_off)
            tvStatusChip.setTextColor(ContextCompat.getColor(this, R.color.chip_off_text))
            btnConnect.text = getString(R.string.btn_connect)
        }
    }

    private fun setHint(message: String) {
        tvHint.text = message
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    /**
     * 朗读识别到的中文（开关打开且 TTS 可用时）。
     */
    private fun speak(text: String) {
        if (!speakEnabled) return
        val engine = tts ?: return
        if (!ttsReady) return

        try {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } catch (e: Exception) {
            Log.w(TAG, "朗读失败", e)
        }
    }
}
