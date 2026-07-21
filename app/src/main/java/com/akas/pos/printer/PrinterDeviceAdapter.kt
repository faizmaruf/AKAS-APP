package com.akas.pos.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.akas.pos.R

class PrinterDeviceAdapter(
    context: Context,
    private val devices: List<BluetoothDevice>,
    private val selectedAddress: String?
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)

    override fun getCount(): Int = devices.size
    override fun getItem(position: Int): BluetoothDevice = devices[position]
    override fun getItemId(position: Int): Long = position.toLong()

    @SuppressLint("MissingPermission")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_printer, parent, false)
        val device = getItem(position)
        view.findViewById<TextView>(R.id.printerName).text = device.name ?: "Perangkat Bluetooth"
        view.findViewById<TextView>(R.id.printerAddress).text = device.address
        view.findViewById<TextView>(R.id.selectedBadge).visibility =
            if (device.address == selectedAddress) View.VISIBLE else View.GONE
        return view
    }
}
