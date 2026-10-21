package com.deibee.voxtypee

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ajustes de la app. La API key se guarda cifrada con una llave del Android Keystore. */
class Ajustes(context: Context) {

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    var clave: String
        get() = prefs.getString(K_CLAVE, null)?.let { descifrar(it) } ?: ""
        set(valor) = prefs.edit().putString(K_CLAVE, if (valor.isBlank()) null else cifrar(valor.trim())).apply()

    val tieneClave: Boolean get() = prefs.contains(K_CLAVE)

    var endpoint: String
        get() = prefs.getString(K_ENDPOINT, null) ?: ENDPOINT_OPENAI
        set(valor) = prefs.edit().putString(K_ENDPOINT, valor.trim().ifBlank { ENDPOINT_OPENAI }).apply()

    var modelo: String
        get() = prefs.getString(K_MODELO, null) ?: MODELO_POR_DEFECTO
        set(valor) = prefs.edit().putString(K_MODELO, valor.trim().ifBlank { MODELO_POR_DEFECTO }).apply()

    var modeloVivo: String
        get() = prefs.getString(K_MODELO_VIVO, null) ?: MODELO_VIVO_POR_DEFECTO
        set(valor) = prefs.edit().putString(K_MODELO_VIVO, valor.trim().ifBlank { MODELO_VIVO_POR_DEFECTO }).apply()

    /** Dónde se ve el texto mientras hablas: [VIVO_CAMPO], [VIVO_FRANJA] o [VIVO_APAGADO]. */
    var vivo: String
        get() = prefs.getString(K_VIVO, VIVO_CAMPO) ?: VIVO_CAMPO
        set(valor) = prefs.edit().putString(K_VIVO, valor).apply()

    /** Al terminar, el audio completo pasa por el modelo final y ese texto es el que queda. */
    var pasadaFinal: Boolean
        get() = prefs.getBoolean(K_PASADA_FINAL, true)
        set(valor) = prefs.edit().putBoolean(K_PASADA_FINAL, valor).apply()

    /** false = usar audífonos si están conectados. */
    var soloMicrofonoTelefono: Boolean
        get() = prefs.getBoolean(K_SOLO_TELEFONO, false)
        set(valor) = prefs.edit().putBoolean(K_SOLO_TELEFONO, valor).apply()

    /** Código ISO del idioma; vacío = que el modelo lo detecte. */
    var idioma: String
        get() = prefs.getString(K_IDIOMA, "es") ?: "es"
        set(valor) = prefs.edit().putString(K_IDIOMA, valor).apply()

    var mantenerParaHablar: Boolean
        get() = prefs.getBoolean(K_MANTENER, false)
        set(valor) = prefs.edit().putBoolean(K_MANTENER, valor).apply()

    var activo: Boolean
        get() = prefs.getBoolean(K_ACTIVO, true)
        set(valor) = prefs.edit().putBoolean(K_ACTIVO, valor).apply()

    var vocabulario: List<String>
        get() = prefs.getString(K_VOCABULARIO, "").orEmpty().lines().filter { it.isNotBlank() }
        set(valor) = prefs.edit().putString(K_VOCABULARIO, valor.joinToString("\n")).apply()

    // Posición de la burbuja: distancia al borde derecho y altura sobre el teclado, en px.
    var margenDerecho: Int
        get() = prefs.getInt(K_MARGEN, -1)
        set(valor) = prefs.edit().putInt(K_MARGEN, valor).apply()

    var alturaSobreTeclado: Int
        get() = prefs.getInt(K_ALTURA, -1)
        set(valor) = prefs.edit().putInt(K_ALTURA, valor).apply()

    private fun llave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun cifrar(texto: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, llave())
        val datos = cipher.iv + cipher.doFinal(texto.toByteArray())
        return Base64.encodeToString(datos, Base64.NO_WRAP)
    }

    private fun descifrar(guardado: String): String? = try {
        val datos = Base64.decode(guardado, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, llave(), GCMParameterSpec(128, datos, 0, 12))
        String(cipher.doFinal(datos, 12, datos.size - 12))
    } catch (e: Exception) {
        null
    }

    companion object {
        const val ENDPOINT_OPENAI = "https://api.openai.com"
        const val MODELO_POR_DEFECTO = "gpt-transcribe"
        const val MODELO_VIVO_POR_DEFECTO = "gpt-live-transcribe"
        const val VIVO_CAMPO = "campo"
        const val VIVO_FRANJA = "franja"
        const val VIVO_APAGADO = "apagado"
        private const val ALIAS = "voxtypee-clave"
        const val K_CLAVE = "clave"
        const val K_ENDPOINT = "endpoint"
        const val K_MODELO = "modelo"
        const val K_MODELO_VIVO = "modelo_vivo"
        const val K_VIVO = "vivo"
        const val K_PASADA_FINAL = "pasada_final"
        const val K_SOLO_TELEFONO = "solo_telefono"
        const val K_IDIOMA = "idioma"
        const val K_MANTENER = "mantener"
        const val K_ACTIVO = "activo"
        const val K_VOCABULARIO = "vocabulario"
        const val K_MARGEN = "margen_derecho"
        const val K_ALTURA = "altura_sobre_teclado"
    }
}
