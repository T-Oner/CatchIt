package com.toner.catchit

import android.content.Intent
import android.net.VpnService
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.toner.catchit.lib.vpn.CatchItVpnService
import com.toner.catchit.ui.main.MainFragment

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        val intent = VpnService.prepare(this)
        if (intent == null) {
            startService(Intent(this, CatchItVpnService::class.java))
        } else {
            startActivityForResult(intent, 0)

        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                    .replace(R.id.container, MainFragment.newInstance())
                    .commitNow()
        }

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        startService(Intent(this, CatchItVpnService::class.java))
    }

}
