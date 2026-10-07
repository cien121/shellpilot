package com.chan.shellpilot

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Registers the full BouncyCastle provider at position 1.
 *
 * Android ships a stripped-down BouncyCastle that lacks modern algorithms
 * (e.g. X25519 for SSH key exchange). SSHJ needs them, so we bundle
 * bcprov-jdk18on and make sure it takes precedence over the system one.
 */
class ShellPilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Remove the crippled Android BC first to avoid confusion, then insert ours at #1.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
