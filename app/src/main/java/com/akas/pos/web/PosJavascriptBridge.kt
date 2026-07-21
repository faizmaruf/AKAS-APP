package com.akas.pos.web

import android.webkit.JavascriptInterface
import com.akas.pos.MainActivity

class PosJavascriptBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun scanBarcode() {
        activity.runOnUiThread { activity.openScanner() }
    }

    @JavascriptInterface
    fun selectPrinter() {
        activity.runOnUiThread { activity.selectPrinter() }
    }

    @JavascriptInterface
    fun printReceipt(text: String) {
        activity.printReceipt(text)
    }

    @JavascriptInterface
    fun getPrinterAddress(): String = activity.getPrinterAddress().orEmpty()

    @JavascriptInterface
    fun getPlatform(): String = "android"
}
