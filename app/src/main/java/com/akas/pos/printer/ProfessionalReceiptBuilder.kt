package com.akas.pos.printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.akas.pos.config.PosConfigLoader
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

object ProfessionalReceiptBuilder {
    private val printerCharset: Charset = Charset.forName("windows-1252")
    private val moneyFormat = DecimalFormat("#,##0", DecimalFormatSymbols().apply {
        groupingSeparator = '.'
        decimalSeparator = ','
    })

    fun build(context: Context, json: String, paperWidthMm: Int): ByteArray {
        val data = JSONObject(json)
        val config = PosConfigLoader.load(context)
        val columns = if (paperWidthMm == 80) 48 else 32
        val smallColumns = if (paperWidthMm == 80) 64 else 42
        val logoWidth = if (paperWidthMm == 80) 220 else 180
        val qrSize = if (paperWidthMm == 80) 260 else 230

        return ByteArrayOutputStream().use { output ->
            output.command(0x1B, 0x40) // Initialize
            output.alignCenter()

            loadConfiguredLogo(context, config.launcherLogo)?.let { logo ->
                output.write(rasterCommand(prepareLogo(logo, logoWidth)))
                output.feed(1)
            }

            output.alignCenter()   // biar text berikutnya juga tetap center

            output.fontA()
            output.bold(true)
            output.text(data.optString("storeName", config.appName).uppercase())
            output.feed(2)
            output.bold(false)

            data.optString("address").takeIf(String::isNotBlank)?.let { address ->
                output.fontB()
                wrap(address, smallColumns).forEach { output.textLine(it) }
            }
            output.fontA()
            output.textLine(divider(columns))

            output.alignLeft()
            output.fontB()
            output.labelValue("NO. INVOICE", data.optString("invoiceNumber", data.optString("invoiceId")), smallColumns)
            output.labelValue("TANGGAL", formatDate(data.optString("date")), smallColumns)
            output.labelValue("CUSTOMER", data.optString("customer", "Umum"), smallColumns)
            output.labelValue("KASIR", data.optString("cashier"), smallColumns)
            output.labelValue("PEMBAYARAN", paymentLabel(data.optString("paymentMethod")), smallColumns)
            output.fontA()
            output.textLine(divider(columns))

            val items = data.optJSONArray("items")
            if (items != null) {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    output.bold(true)
                    wrap(item.optString("name", "Produk"), columns).forEach { output.textLine(it) }
                    output.bold(false)
                    val qty = item.optDouble("qty", 1.0)
                    val price = item.optDouble("price", 0.0)
                    val subtotal = item.optDouble("subtotal", qty * price)
                    output.fontB()
                    output.textLine(sides("${number(qty)} x ${money(price)}", money(subtotal), smallColumns))
                    output.fontA()
                }
            }

            output.textLine(divider(columns))
            output.fontB()
            output.amountLine("Subtotal", data, "subtotal", smallColumns)
            output.amountLine("Diskon", data, "discount", smallColumns, hideZero = true)
            output.amountLine("Pajak", data, "tax", smallColumns, hideZero = true)
            output.fontA()
            output.bold(true)
            output.doubleHeight(true)
            output.textLine(sides("TOTAL", money(data.optDouble("total", 0.0)), columns))
            output.doubleHeight(false)
            output.bold(false)
            output.fontB()
            output.amountLine("Bayar", data, "paid", smallColumns)
            output.amountLine("Kembali", data, "change", smallColumns)
            output.fontA()
            output.textLine(divider(columns))

            output.alignCenter()
            output.bold(true)
            output.textLine(data.optString("footer", "Terima kasih"))
            output.bold(false)
            output.fontB()
            output.textLine("Terimakasih")
            output.feed(4)
//            output.write(rasterCommand(createQrBitmap(config.startUrl, qrSize)))
//            output.feed(1)
//            output.textLine(config.startUrl)
//            output.feed(4)
//            output.command(0x1D, 0x56, 0x01) // Partial cut; ignored by printers without cutter
            output.toByteArray()
        }
    }

    private fun loadConfiguredLogo(context: Context, configuredPath: String): Bitmap? {
        val resourceName = configuredPath.substringAfter('/').substringBeforeLast('.')
        val resourceId = context.resources.getIdentifier(resourceName, "drawable", context.packageName)
        return resourceId.takeIf { it != 0 }?.let { BitmapFactory.decodeResource(context.resources, it) }
    }

    private fun prepareLogo(source: Bitmap, targetWidth: Int): Bitmap {
        val ratio = targetWidth.toFloat() / source.width.coerceAtLeast(1)
        val targetHeight = (source.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        return Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { result ->
            Canvas(result).apply {
                drawColor(Color.WHITE)
                drawBitmap(scaled, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
            }
        }
    }

    private fun createQrBitmap(value: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until size) {
                for (x in 0 until size) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }

    private fun rasterCommand(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerRow = (width + 7) / 8
        val imageData = ByteArray(bytesPerRow * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                if (alpha > 32 && luminance < 160) {
                    val index = y * bytesPerRow + x / 8
                    imageData[index] = (imageData[index].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
        }
        return ByteArrayOutputStream().use { output ->
            output.command(0x1D, 0x76, 0x30, 0x00)
            output.command(bytesPerRow and 0xFF, bytesPerRow shr 8, height and 0xFF, height shr 8)
            output.write(imageData)
            output.toByteArray()
        }
    }

    private fun ByteArrayOutputStream.text(value: String) = write(value.toByteArray(printerCharset))
    private fun ByteArrayOutputStream.textLine(value: String) { text(value); feed(1) }
    private fun ByteArrayOutputStream.feed(lines: Int) = repeat(lines) { write(0x0A) }
    private fun ByteArrayOutputStream.command(vararg values: Int) = values.forEach(::write)
    private fun ByteArrayOutputStream.alignLeft() = command(0x1B, 0x61, 0x00)
    private fun ByteArrayOutputStream.alignCenter() = command(0x1B, 0x61, 0x01)
    private fun ByteArrayOutputStream.fontA() = command(0x1B, 0x4D, 0x00)
    private fun ByteArrayOutputStream.fontB() = command(0x1B, 0x4D, 0x01)
    private fun ByteArrayOutputStream.bold(enabled: Boolean) = command(0x1B, 0x45, if (enabled) 1 else 0)
    private fun ByteArrayOutputStream.doubleSize(enabled: Boolean) = command(0x1D, 0x21, if (enabled) 0x11 else 0x00)
    private fun ByteArrayOutputStream.doubleHeight(enabled: Boolean) = command(0x1D, 0x21, if (enabled) 0x01 else 0x00)

    private fun ByteArrayOutputStream.labelValue(label: String, value: String, columns: Int) {
        if (value.isNotBlank()) textLine(sides(label, value, columns))
    }

    private fun ByteArrayOutputStream.amountLine(
        label: String,
        data: JSONObject,
        key: String,
        columns: Int,
        hideZero: Boolean = false
    ) {
        if (!data.has(key) || data.isNull(key)) return
        val value = data.optDouble(key, 0.0)
        if (hideZero && value == 0.0) return
        textLine(sides(label, money(value), columns))
    }

    private fun divider(columns: Int): String = "-".repeat(columns)
    private fun sides(left: String, right: String, columns: Int): String {
        val safeRight = right.take(columns)
        if (safeRight.length == columns) return safeRight
        val safeLeft = left.take((columns - safeRight.length - 1).coerceAtLeast(0))
        return safeLeft + " ".repeat((columns - safeLeft.length - safeRight.length).coerceAtLeast(1)) + safeRight
    }

    private fun wrap(value: String, columns: Int): List<String> {
        if (value.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var current = ""
        value.trim().split(Regex("\\s+")).forEach { word ->
            if (word.length > columns) {
                if (current.isNotEmpty()) result += current
                word.chunked(columns).forEach { result += it }
                current = ""
            } else if (current.isEmpty()) current = word
            else if (current.length + word.length + 1 <= columns) current += " $word"
            else { result += current; current = word }
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun money(value: Double): String = "Rp ${moneyFormat.format(value)}"
    private fun number(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    private fun paymentLabel(value: String): String = when (value.lowercase()) {
        "cash" -> "Tunai"
        "qris" -> "QRIS"
        "card" -> "Kartu"
        "transfer" -> "Transfer"
        else -> value
    }

    private fun formatDate(value: String): String {
        if (value.isBlank()) return "-"
        val outputFormat = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale("id", "ID"))
        val parsers = listOf<(String) -> LocalDateTime>(
            { OffsetDateTime.parse(it).toLocalDateTime() },
            { LocalDateTime.parse(it, DateTimeFormatter.ISO_LOCAL_DATE_TIME) },
            { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) },
            { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) },
            { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay() }
        )
        for (parser in parsers) {
            try {
                return parser(value.trim()).format(outputFormat)
            } catch (_: DateTimeParseException) {
                // Try the next known API date format.
            }
        }
        return value.replace('T', ' ').substringBefore('.').take(24)
    }
}
