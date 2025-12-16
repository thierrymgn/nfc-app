package com.example.myapplication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var isWriteMode = false

    private lateinit var nfcContentTextView: TextView
    private lateinit var writeTagButton: Button
    private lateinit var actionSelectorSpinner: Spinner

    private lateinit var timerFormGroup: CardView
    private lateinit var timerDurationInput: EditText
    private lateinit var timerMessageInput: EditText

    private lateinit var urlFormGroup: CardView
    private lateinit var urlInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nfcContentTextView = findViewById(R.id.nfc_content)
        writeTagButton = findViewById(R.id.write_tag_button)
        actionSelectorSpinner = findViewById(R.id.action_selector_spinner)
        timerFormGroup = findViewById(R.id.timer_form_group)
        timerDurationInput = findViewById(R.id.timer_duration_input)
        timerMessageInput = findViewById(R.id.timer_message_input)
        urlFormGroup = findViewById(R.id.url_form_group)
        urlInput = findViewById(R.id.url_input)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC n'est pas disponible sur cet appareil", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupSpinner()
        setupWriteButton()
    }

    private fun setupSpinner() {
        val actions = arrayOf("Minuteur", "Ouvrir une URL", "Message Texte")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, actions)
        actionSelectorSpinner.adapter = adapter

        actionSelectorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFormVisibility(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { /* Ne rien faire */ }
        }
    }

    private fun updateFormVisibility(position: Int) {
        timerFormGroup.visibility = if (position == 0) View.VISIBLE else View.GONE
        urlFormGroup.visibility = if (position == 1) View.VISIBLE else View.GONE
    }

    private fun setupWriteButton() {
        writeTagButton.setOnClickListener {
            isWriteMode = !isWriteMode
            if (isWriteMode) {
                writeTagButton.text = "Désactiver l'écriture"
                nfcContentTextView.text = "Approchez un tag pour écrire..."
                actionSelectorSpinner.visibility = View.VISIBLE
                updateFormVisibility(actionSelectorSpinner.selectedItemPosition)
            } else {
                writeTagButton.text = "Activer l'écriture"
                nfcContentTextView.text = "Approchez un tag NFC"
                actionSelectorSpinner.visibility = View.GONE
                timerFormGroup.visibility = View.GONE
                urlFormGroup.visibility = View.GONE
            }
        }
    }

    private fun generateJsonFromUi(): String? {
        val selectedAction = actionSelectorSpinner.selectedItemPosition
        val json = JSONObject()

        try {
            when (selectedAction) {
                0 -> {
                    val duration = timerDurationInput.text.toString()
                    val message = timerMessageInput.text.toString()
                    if (duration.isBlank()) {
                        Toast.makeText(this, "La durée ne peut pas être vide", Toast.LENGTH_SHORT).show()
                        return null
                    }
                    json.put("action", "set_timer")
                    json.put("duration", duration.toInt())
                    json.put("message", message.ifBlank { "Minuteur NFC" })
                }
                1 -> {
                    val url = urlInput.text.toString()
                    if (url.isBlank() || !url.startsWith("https://")) {
                        Toast.makeText(this, "L'URL est invalide", Toast.LENGTH_SHORT).show()
                        return null
                    }
                    json.put("action", "open_url")
                    json.put("url", url)
                }
                else -> return null
            }
        } catch (e: JSONException) {
            Log.e("JsonGenerator", "Erreur lors de la création du JSON", e)
            return null
        }
        return json.toString()
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
                val json = generateJsonFromUi() // ** UTILISATION DE LA NOUVELLE FONCTION **
                if (json == null) {
                    Toast.makeText(this, "Génération du JSON annulée.", Toast.LENGTH_SHORT).show()
                    return@let
                }
                val result = NfcWriter.write(it, json)
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
            }
        } else {
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
                "open_url" -> openUrl(context, json)
                else -> {
                    Toast.makeText(context, "Action non reconnue: $action", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: JSONException) {
            Toast.makeText(context, "Contenu non-JSON détecté", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setTimer(context: Context, json: JSONObject) {
        val duration = json.optInt("duration", 0) // Lit la durée, 0 par défaut
        val message = json.optString("message", "Minuteur NFC") // Lit le message, avec un défaut

        if (duration <= 0) {
            Toast.makeText(context, "Durée du minuteur invalide", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_LENGTH, duration)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }

        val activity = intent.resolveActivity(context.packageManager)
        if (activity != null) {
            context.startActivity(intent)
            Toast.makeText(context, "Minuteur '${message}' lancé pour ${duration}s", Toast.LENGTH_LONG).show()
        } else {
            Log.e(TAG, "resolveActivity a retourné null pour le minuteur. VÉRIFIEZ LE MANIFEST.")
            Toast.makeText(context, "Aucune application d'horloge trouvée", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(context: Context, json: JSONObject) {
        val url = json.optString("url")
        if (url.isBlank()) {
            Toast.makeText(context, "URL invalide ou manquante", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val activity = intent.resolveActivity(context.packageManager)

        if (activity != null) {
            context.startActivity(intent)
        } else {
            Log.e(TAG, "resolveActivity a retourné null pour l'URL. VÉRIFIEZ LE MANIFEST.")
            Toast.makeText(context, "Aucune application de navigateur trouvée", Toast.LENGTH_SHORT).show()
        }
    }
}