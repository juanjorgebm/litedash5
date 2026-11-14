package cr.juegos.carro

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Conexiones HTTPS que funcionan en pantallas viejas (Android 5.1):
 * - además de los certificados del sistema, acepta raíces públicas actuales que vienen en assets/raices.pem;
 * - si el reloj del carro está mal, valida con la hora que da el servidor.
 */
object Net {
    @Volatile private var factory: SSLSocketFactory? = null
    @Volatile private var netDate: Date? = null

    /** Hora real tomada del encabezado Date de una petición sin cifrar. */
    private fun serverDate(): Date? {
        netDate?.let { return it }
        return try {
            val c = URL("http://github.com/").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000; c.instanceFollowRedirects = false
            c.requestMethod = "HEAD"
            c.responseCode
            val d = c.date; c.disconnect()
            if (d > 0) Date(d).also { netDate = it } else null
        } catch (e: Exception) { null }
    }

    private class Trust(val system: X509TrustManager?, val anchors: Set<TrustAnchor>) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { system?.checkClientTrusted(chain, authType) }
        override fun getAcceptedIssuers(): Array<X509Certificate> = system?.acceptedIssuers ?: emptyArray()

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val first = try { system?.checkServerTrusted(chain, authType); return } catch (e: CertificateException) { e }
            val cf = CertificateFactory.getInstance("X.509")
            val validator = CertPathValidator.getInstance("PKIX")
            val dates = listOfNotNull(serverDate(), Date())
            for (date in dates) for (k in chain.size downTo 1) {
                try {
                    val params = PKIXParameters(anchors)
                    params.isRevocationEnabled = false
                    params.date = date
                    validator.validate(cf.generateCertPath(chain.take(k)), params)
                    return
                } catch (e: Exception) { /* siguiente intento */ }
            }
            throw first ?: CertificateException("Certificado no confiable")
        }
    }

    fun socketFactory(ctx: Context): SSLSocketFactory {
        factory?.let { return it }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val system = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        val anchors = HashSet<TrustAnchor>()
        system?.acceptedIssuers?.forEach { anchors.add(TrustAnchor(it, null)) }
        try {
            val cf = CertificateFactory.getInstance("X.509")
            ctx.assets.open("raices.pem").use { inp ->
                for (cert in cf.generateCertificates(inp)) anchors.add(TrustAnchor(cert as X509Certificate, null))
            }
        } catch (e: Exception) { }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf(Trust(system, anchors)), null)
        return ssl.socketFactory.also { factory = it }
    }

    /** Texto corto con la causa real de un error de red, para mostrarlo en pantalla. */
    fun reason(e: Throwable): String {
        var t: Throwable = e
        while (t.cause != null && t.cause !== t) t = t.cause!!
        val msg = (t.message ?: "").take(70)
        return t.javaClass.simpleName + if (msg.isNotEmpty()) ": $msg" else ""
    }
}
