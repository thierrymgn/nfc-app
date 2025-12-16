package com.example.myapplication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var nfcContentTextView: TextView
    private lateinit var jsonInput: EditText
    private lateinit var writeTagButton: Button

    private var isWriteMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nfcContentTextView = findViewById(R.id.nfc_content)
        jsonInput = findViewById(R.id.json_input)
        writeTagButton = findViewById(R.id.write_tag_button)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC n'est pas disponible sur cet appareil", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        writeTagButton.setOnClickListener {
            isWriteMode = !isWriteMode
            if (isWriteMode) {
                writeTagButton.text = "Désactiver l'écriture"
                nfcContentTextView.text = "Approchez un tag pour écrire..."
                jsonInput.setText("{ \"action\": \"set_timer\", \"duration\": 600, \"message\": \"Pâtes\" }")
            } else {
                writeTagButton.text = "Activer l'écriture"
                nfcContentTextView.text = "Approchez un tag NFC"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

        if (isWriteMode) {
            tag?.let {
                val json = jsonInput.text.toString()
                if (json.isBlank()) {
                    Toast.makeText(this, "Le JSON ne peut pas être vide", Toast.LENGTH_SHORT).show()
                    return@let
                }
                val result = NfcWriter.write(it, json)
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
            }
        } else {
            // Mode lecture
            if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action) {
                val ndefMessages: Array<NdefMessage>? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.mapNotNull { it as? NdefMessage }?.toTypedArray()
                    }

                ndefMessages?.firstOrNull()?.let {
                    parseNdefMessage(it)
                } ?: run {
                    nfcContentTextView.text = "Aucun message NDEF trouvé dans l'intent."
                }
            } else {
                tag?.let { readTag(it) } ?: run {
                    nfcContentTextView.text = "Tag non supporté ou non détecté."
                }
            }
        }
    }

    private fun parseNdefMessage(ndefMessage: NdefMessage) {
        val records = ndefMessage.records
        if (records.isNotEmpty()) {
            val record = records[0]
            if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
                try {
                    val payload = record.payload
                    if (payload.isEmpty()) {
                        nfcContentTextView.text = "Payload de record texte vide"
                        return
                    }
                    val status = payload[0].toInt()
                    val langCodeLength = status and 0x3F
                    val textEncoding = if ((status and 0x80) == 0) Charsets.UTF_8 else Charsets.UTF_16
                    if (payload.size <= langCodeLength + 1) {
                        nfcContentTextView.text = "Payload de record texte malformé"
                        return
                    }
                    val text = String(payload, langCodeLength + 1, payload.size - langCodeLength - 1, textEncoding)
                    
                    // ** INTÉGRATION DE L'ACTION MANAGER **
                    nfcContentTextView.text = text // Affiche le JSON lu
                    ActionManager.executeAction(this, text) // Exécute l'action correspondante

                } catch (e: Exception) {
                    nfcContentTextView.text = "Erreur de lecture du record texte"
                    Toast.makeText(applicationContext, "Erreur: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                nfcContentTextView.text = "Le premier record n'est pas un record texte."
            }
        } else {
            nfcContentTextView.text = "Message NDEF vide."
        }
    }

    private fun readTag(tag: Tag) {
        val ndef = Ndef.get(tag)
        ndef?.use {
            try {
                it.connect()
                val ndefMessage = it.ndefMessage
                if (ndefMessage != null) {
                    parseNdefMessage(ndefMessage)
                } else {
                    nfcContentTextView.text = "Tag formaté NDEF, mais message vide."
                }
            } catch (e: IOException) {
                nfcContentTextView.text = "Lecture du tag échouée."
                Toast.makeText(applicationContext, "Erreur I/O: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } ?: run {
            nfcContentTextView.text = "Ce tag ne supporte pas NDEF."
        }
    }
}

object NfcWriter {

    enum class WriteResult(val message: String) {
        SUCCESS("Tag écrit avec succès !"),
        ERROR_READ_ONLY("Erreur: Le tag est en lecture seule."),
        ERROR_INSUFFICIENT_SPACE("Erreur: Espace insuffisant sur le tag."),
        ERROR_IO("Erreur d'E/S lors de la connexion au tag."),
        ERROR_FORMAT("Erreur: Le message NDEF est malformé."),
        ERROR_UNSUPPORTED("Erreur: Ce type de tag n'est pas supporté pour l'écriture NDEF."),
        ERROR_UNKNOWN("Erreur inconnue.")
    }

    fun write(tag: Tag, data: String): WriteResult {
        val ndefRecord = NdefRecord.createTextRecord("en", data)
        val ndefMessage = NdefMessage(arrayOf(ndefRecord))

        return Ndef.get(tag)?.let {
            try {
                it.connect()
                if (!it.isWritable) {
                    return@let WriteResult.ERROR_READ_ONLY
                }
                if (it.maxSize < ndefMessage.toByteArray().size) {
                    return@let WriteResult.ERROR_INSUFFICIENT_SPACE
                }
                it.writeNdefMessage(ndefMessage)
                WriteResult.SUCCESS
            } catch (e: IOException) {
                WriteResult.ERROR_IO
            } catch (e: FormatException) {
                WriteResult.ERROR_FORMAT
            } finally {
                it.close()
            }
        } ?: WriteResult.ERROR_UNSUPPORTED
    }
}

object ActionManager {

    private const val TAG = "ActionManager"

    fun executeAction(context: Context, jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val action = json.optString("action")

            when (action) {
                "set_timer" -> setTimer(context, json)
                else -> {
                    Toast.makeText(context, "Action non reconnue: $action", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: JSONException) {
            Toast.makeText(context, "Contenu non-JSON détecté", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setTimer(context: Context, json: JSONObject) {
        val duration = json.optInt("duration", 0)
        val message = json.optString("message", "Minuteur NFC")

        if (duration <= 0) {
            Toast.makeText(context, "Durée invalide", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_LENGTH, duration)
        }

        try {
            // On tente de lancer directement. Si l'app n'est pas trouvée, cela lèvera une exception.
            context.startActivity(intent)
            Toast.makeText(context, "Minuteur lancé : ${duration}s", Toast.LENGTH_SHORT).show()
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "Aucune application trouvée pour gérer le minuteur.", e)
            Toast.makeText(context, "Erreur : Aucune application d'horloge installée", Toast.LENGTH_LONG).show()
        }
    }
}
