package com.example.innerprinter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.UUID

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var printer: BluetoothDevice? = null
    private var bitmap: Bitmap? = null
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var rb58: RadioButton
    private lateinit var rb80: RadioButton
    private lateinit var safeMode: CheckBox
    private lateinit var bigToken: CheckBox
    private lateinit var darkness: SeekBar
    private lateinit var textInput: EditText
    private lateinit var tokenInput: EditText

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { bitmap = BitmapFactory.decodeStream(it, null, opts) }
        val bmp = bitmap ?: run { status.text = "تعذر فتح الصورة"; return@registerForActivityResult }
        preview.setImageBitmap(bmp)
        status.text = "تم اختيار الصورة، جاري البحث عن رقم التغذية..."
        detectTokens(bmp)
    }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        findPrinter()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        val scroll = ScrollView(this).apply { addView(root) }

        status = TextView(this).apply { text = "اضغط (بحث عن الطابعة)"; textSize = 16f }
        val btnFind = Button(this).apply { text = "بحث عن الطابعة الداخلية"; setOnClickListener { checkPermsThenFind() } }

        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; gravity = Gravity.CENTER }
        rb58 = RadioButton(this).apply { text = "ورق 58mm"; id = 1 }
        rb80 = RadioButton(this).apply { text = "ورق 80mm"; id = 2 }
        group.addView(rb58); group.addView(rb80)
        if (prefs.getInt("width", 384) == 576) rb80.isChecked = true else rb58.isChecked = true
        safeMode = CheckBox(this).apply { text = "وضع آمن (أبطأ، يمنع الرموز الغريبة)"; isChecked = prefs.getBoolean("safe", true) }
        val darkLabel = TextView(this).apply { text = "كثافة الطباعة" }
        darkness = SeekBar(this).apply { max = 100; progress = prefs.getInt("dark", 60) }

        val btnPick = Button(this).apply { text = "اختيار صورة الإيصال"; setOnClickListener { pickImage.launch("image/*") } }
        preview = ImageView(this).apply { adjustViewBounds = true; maxHeight = 600 }

        val tokenLabel = TextView(this).apply {
            text = "رقم التغذية (يُقرأ تلقائيًا من الصورة، راجعه بعينك قبل الطباعة وصحّحه إن لزم):"
        }
        tokenInput = EditText(this).apply {
            hint = "0000-0000-0000-0000-0000"
            typeface = Typeface.MONOSPACE
            minLines = 2
            gravity = Gravity.TOP
        }
        bigToken = CheckBox(this).apply { text = "طباعة رقم التغذية بخط كبير أسفل الإيصال"; isChecked = prefs.getBoolean("big", true) }

        val btnPrint = Button(this).apply { text = "طباعة الإيصال"; setOnClickListener { printReceipt() } }
        val btnTokenOnly = Button(this).apply { text = "طباعة رقم التغذية فقط"; setOnClickListener { printTokenOnly() } }

        textInput = EditText(this).apply { hint = "أو اكتب نصًا حرًا هنا (عربي / English)"; minLines = 3; gravity = Gravity.TOP }
        val btnText = Button(this).apply {
            text = "طباعة النص"
            setOnClickListener {
                val t = textInput.text.toString()
                if (t.isBlank()) { status.text = "اكتب نصًا أولاً"; return@setOnClickListener }
                val bmp = TextRender.render(t, currentWidth())
                preview.setImageBitmap(bmp)
                printBitmap(bmp)
            }
        }

        listOf(status, btnFind, group, safeMode, darkLabel, darkness, btnPick, preview,
            tokenLabel, tokenInput, bigToken, btnPrint, btnTokenOnly, textInput, btnText)
            .forEach { root.addView(it) }
        setContentView(scroll)
        checkPermsThenFind()
    }

    private fun currentWidth() = if (rb80.isChecked) 576 else 384

    /** قراءة رقم التغذية من الصورة (سطر من 4-5 مجموعات، كل مجموعة 4 أرقام) */
    private fun detectTokens(bmp: Bitmap) {
        val strict = Regex("^\\d{4}(-\\d{4}){3,4}$")
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { res ->
                val found = mutableListOf<String>()
                for (b in res.textBlocks) for (l in b.lines) {
                    val t = l.text.replace(" ", "").replace('–', '-').replace('—', '-').replace('−', '-')
                    if (strict.matches(t) && t.any { it.isDigit() && it != '0' } && t !in found) found.add(t)
                }
                tokenInput.setText(found.joinToString("\n"))
                status.text = if (found.isNotEmpty()) "تم العثور على رقم التغذية، راجعه جيدًا ✔" else "لم يتم العثور على رقم التغذية، اكتبه يدويًا إن أردت"
            }
            .addOnFailureListener { status.text = "تعذرت قراءة الرقم تلقائيًا، اكتبه يدويًا إن أردت" }
    }

    private fun printReceipt() {
        val img = bitmap ?: run { status.text = "اختر صورة أولاً"; return }
        val width = currentWidth()
        val parts = mutableListOf(EscPos.trimWhite(img))
        val tokens = TokenRender.parse(tokenInput.text.toString())
        if (bigToken.isChecked && tokens.isNotEmpty()) parts.add(TokenRender.render(tokens, width))
        printBitmap(EscPos.stack(parts, width))
    }

    private fun printTokenOnly() {
        val tokens = TokenRender.parse(tokenInput.text.toString())
        if (tokens.isEmpty()) { status.text = "لا يوجد رقم تغذية صالح (16-20 رقمًا)"; return }
        val bmp = TokenRender.render(tokens, currentWidth())
        preview.setImageBitmap(bmp)
        printBitmap(bmp)
    }

    private fun checkPermsThenFind() {
        if (Build.VERSION.SDK_INT >= 31) {
            val need = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (need.isNotEmpty()) { askPerms.launch(need.toTypedArray()); return }
        }
        findPrinter()
    }

    private fun findPrinter() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) { status.text = "فعّل البلوتوث أولاً"; return }
        val bonded = adapter.bondedDevices.toList()
        val saved = prefs.getString("addr", null)
        printer = bonded.firstOrNull { it.address == saved }
            ?: bonded.firstOrNull {
                val n = (it.name ?: "").lowercase()
                n.contains("inner") || n.contains("printer") || it.address == "00:11:22:33:44:55"
            }
        if (printer != null) {
            prefs.edit().putString("addr", printer!!.address).apply()
            status.text = "الطابعة: ${printer!!.name} (${printer!!.address})"
        } else if (bonded.isEmpty()) {
            status.text = "لا توجد أجهزة مقترنة"
        } else {
            val names = bonded.map { "${it.name} - ${it.address}" }.toTypedArray()
            android.app.AlertDialog.Builder(this).setTitle("اختر الطابعة")
                .setItems(names) { _, i ->
                    printer = bonded[i]
                    prefs.edit().putString("addr", bonded[i].address).apply()
                    status.text = "الطابعة: ${names[i]}"
                }.show()
        }
    }

    private fun connect(dev: BluetoothDevice): BluetoothSocket {
        BluetoothAdapter.getDefaultAdapter().cancelDiscovery()
        return try {
            dev.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
        } catch (e: Exception) {
            dev.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() }
        }
    }

    private fun printBitmap(bmp: Bitmap) {
        val dev = printer ?: run { status.text = "لم يتم تحديد الطابعة"; return }
        val width = currentWidth()
        val threshold = 90 + darkness.progress
        val slow = safeMode.isChecked
        prefs.edit().putInt("width", width).putBoolean("safe", slow)
            .putInt("dark", darkness.progress).putBoolean("big", bigToken.isChecked).apply()
        val packet = if (slow) 128 else 256
        val delay = if (slow) 40L else 15L
        val gap = if (slow) 120L else 40L
        status.text = "جاري الطباعة..."
        Thread {
            try {
                val chunks = EscPos.rasterChunks(bmp, width, threshold)
                connect(dev).use { s ->
                    val os = s.outputStream
                    os.write(EscPos.INIT); os.flush(); Thread.sleep(150)
                    for ((i, c) in chunks.withIndex()) {
                        EscPos.send(os, c, packet, delay)
                        Thread.sleep(gap)
                        if (i % 10 == 0) runOnUiThread { status.text = "جاري الطباعة ${i * 100 / chunks.size}%" }
                    }
                    EscPos.send(os, EscPos.feed(4), 64, 10)
                    Thread.sleep(1000)
                }
                runOnUiThread { status.text = "تمت الطباعة ✔" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "فشل: ${e.message}" }
            }
        }.start()
    }
}
