package com.toner.catchit.lib.vpn

import android.content.Intent
import android.net.VpnService
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class CatchItVpnService : VpnService() {

    val TAG = "CatchItVpnService"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        val builder = Builder()
        val establish = builder.addAddress("192.168.2.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("192.168.1.1")
            .establish()
        Thread{
            val inputStream = FileInputStream(establish.fileDescriptor)
            val outputStream = FileOutputStream(establish.fileDescriptor);
            val buffer = ByteBuffer.allocate(32767)
            val length = inputStream.read(buffer.array())
            Log.d(TAG, String(buffer.array()))
            outputStream.write(buffer.array(), 0, length)

        }.run()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onRevoke() {
        super.onRevoke()
        Log.d(TAG, "onRevoke")
    }
}