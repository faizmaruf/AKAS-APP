package com.akas.pos.printer

import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

object ReceiptFormatter {
    private val moneyFormat = DecimalFormat("#,##0", DecimalFormatSymbols().apply {
        groupingSeparator = '.'
        decimalSeparator = ','
    })

    fun format(json: String, paperColumns: Int = 32): String {
        val data = JSONObject(json)
        data.optString("receiptText").takeIf { it.isNotBlank() }?.let { return it }
        val width = paperColumns.coerceIn(32, 48)

        return buildString {
            center(data.optString("storeName", "WINDU KOPI"), width).also(::appendLine)
            data.optString("address").takeIf { it.isNotBlank() }?.let { center(it, width).also(::appendLine) }
            appendLine(line(width))
            field("Invoice", data.optString("invoiceNumber", data.optString("invoiceId")), width)
            field("Tanggal", data.optString("date"), width)
            field("Kasir", data.optString("cashier"), width)
            appendLine(line(width))

            val items = data.optJSONArray("items")
            if (items != null) {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    appendLine(item.optString("name", "Item").take(width))
                    val quantity = item.optDouble("qty", 1.0)
                    val price = item.optDouble("price", 0.0)
                    val subtotal = item.optDouble("subtotal", quantity * price)
                    appendLine(sides("${number(quantity)} x ${money(price)}", money(subtotal), width))
                }
            }

            appendLine(line(width))
            amountField("Subtotal", data, "subtotal", width)
            amountField("Diskon", data, "discount", width)
            amountField("Pajak", data, "tax", width)
            amountField("TOTAL", data, "total", width)
            field("Pembayaran", data.optString("paymentMethod"), width)
            amountField("Bayar", data, "paid", width)
            amountField("Kembali", data, "change", width)
            appendLine(line(width))
            center(data.optString("footer", "Terima kasih"), width).also(::appendLine)
        }
    }

    private fun StringBuilder.field(label: String, value: String, width: Int) {
        if (value.isNotBlank()) appendLine(sides(label, value, width))
    }

    private fun StringBuilder.amountField(label: String, data: JSONObject, key: String, width: Int) {
        if (data.has(key) && !data.isNull(key)) appendLine(sides(label, money(data.optDouble(key)), width))
    }

    private fun line(width: Int): String = "-".repeat(width)
    private fun center(value: String, width: Int): String =
        value.take(width).padStart((width + value.take(width).length) / 2)

    private fun sides(left: String, right: String, width: Int): String {
        val safeRight = right.take(width)
        if (safeRight.length == width) return safeRight
        val maxLeft = (width - safeRight.length - 1).coerceAtLeast(0)
        val safeLeft = left.take(maxLeft)
        return safeLeft + " ".repeat((width - safeLeft.length - safeRight.length).coerceAtLeast(1)) + safeRight
    }

    private fun money(value: Double): String = "Rp ${moneyFormat.format(value)}"
    private fun number(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
}
