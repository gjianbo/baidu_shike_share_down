package com.hnwd.bdplayer.extension

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

internal const val HOST_PACKAGE = "com.hnwd.bdplayer"

class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_VIEW) {
            Toast.makeText(this, R.string.activation_ready, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (savedInstanceState == null && intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull()
            val link = sharedText?.let(MediaLink::fromShare)
            when {
                link == null -> showMessage(R.string.unsupported_link)
                packageManager.checkSignatures(packageName, HOST_PACKAGE) != PackageManager.SIGNATURE_MATCH ->
                    showMessage(R.string.host_unavailable)
                else -> {
                    val forwarded = Intent("com.hnwd.bdplayer.action.EXTENSION_SHARE").apply {
                        component = ComponentName(HOST_PACKAGE, "com.hnwd.bdplayer.ExtensionShareActivity")
                        putExtra(Intent.EXTRA_TEXT, link.url)
                    }
                    runCatching { startActivity(forwarded) }.onFailure { showMessage(R.string.host_unavailable) }
                }
            }
        }
        finish()
    }

    private fun showMessage(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
}
