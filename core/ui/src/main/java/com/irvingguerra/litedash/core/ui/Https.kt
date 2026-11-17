package com.irvingguerra.litedash.core.ui

import android.content.Context
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Conexiones HTTPS que también sirven en Android 6 y 7.0. Las descargas de GitHub y el clima
 * (Open-Meteo) usan Let's Encrypt, cuya raíz (ISRG Root X1/X2) Android trae desde la 7.1.1;
 * antes, y sin network_security_config (es de Android 7), se confía en las raíces del sistema
 * MÁS esas dos y las de GitHub (USERTrust, Sectigo, DigiCert G2, Amazon: sus raíces nuevas no están en Android 5), que vienen en `res/raw/isrg_roots.pem` (sacadas de las raíces de macOS; huellas
 * SHA-256 96:BC:EC…:08:C6 y 69:72:9B…:14:70). De la API 26 en adelante no cambia nada.
 *
 * Vive en core/ui porque es el único módulo que comparten app, feature/car y feature/updater
 * (por eso también aquí la descarga verificada que usan los autos y las actualizaciones).
 */
object Https {
    @Volatile private var legacy: SSLSocketFactory? = null

    fun open(context: Context, url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        // Las redirecciones (github.com → release-assets) siguen con la misma configuración.
        // Hasta la API 25 inclusive: Android 7.1.0 también es 25 y no trae ISRG.
        if (connection is HttpsURLConnection && Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            connection.sslSocketFactory = legacyFactory(context.applicationContext)
        }
        return connection
    }

    /** GET de una descarga de LiteDash (catálogo, autos, actualizaciones); truena si no contesta 2xx. */
    fun get(context: Context, url: String, accept: String? = null): HttpURLConnection =
        open(context, url).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LiteDash")
            accept?.let { setRequestProperty("Accept", it) }
            if (responseCode !in 200..299) error("HTTP $responseCode")
        }

    /**
     * Baja [url] a [target] y devuelve su SHA-256 en hexadecimal (el que llama lo compara).
     * [progress] va de 0 a 1 cuando se sabe el tamaño ([bytes] > 0).
     */
    fun download(context: Context, url: String, target: File, bytes: Long, accept: String?, progress: (Float) -> Unit): String {
        val sha = MessageDigest.getInstance("SHA-256")
        get(context, url, accept).inputStream.use { input ->
            target.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    sha.update(buf, 0, n)
                    total += n
                    if (bytes > 0) progress((total.toFloat() / bytes).coerceAtMost(1f))
                }
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    private fun legacyFactory(context: Context): SSLSocketFactory =
        legacy ?: synchronized(this) { legacy ?: build(context).also { legacy = it } }

    private fun build(context: Context): SSLSocketFactory {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        // Solo las raíces del sistema ("system:…"; las que instala el usuario, "user:…", no:
        // desde Android 7 las apps no confían en ellas)…
        val system = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
        for (alias in system.aliases()) if (alias.startsWith("system:")) store.setCertificateEntry(alias, system.getCertificate(alias))
        // …más las de Let's Encrypt.
        context.resources.openRawResource(R.raw.isrg_roots).use { input ->
            CertificateFactory.getInstance("X.509").generateCertificates(input)
                .forEachIndexed { i, cert -> store.setCertificateEntry("isrg-$i", cert) }
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }
}
