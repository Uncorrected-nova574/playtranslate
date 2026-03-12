package com.gamelens.ui

import android.content.DialogInterface
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.gamelens.AnkiManager
import com.gamelens.Prefs
import com.gamelens.R
import com.gamelens.fullScreenDialogTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WordAnkiReviewSheet : DialogFragment() {

    private var includePhoto = true
    private var deckEntries: List<Map.Entry<Long, String>> = emptyList()
    /** Optional listener called when this sheet is dismissed (used by WordAnkiReviewActivity). */
    var onDismissListener: DialogInterface.OnDismissListener? = null

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_word_anki_review, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideRight)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnBackWordAnki).setOnClickListener { dismiss() }

        val args           = arguments ?: return
        val word           = args.getString(ARG_WORD) ?: return
        val reading        = args.getString(ARG_READING) ?: ""
        val pos            = args.getString(ARG_POS) ?: ""
        val definition     = args.getString(ARG_DEFINITION) ?: ""
        val screenshotPath = args.getString(ARG_SCREENSHOT_PATH)

        val spinnerDeck    = view.findViewById<Spinner>(R.id.spinnerWordAnkiDeck)
        val tvHeadword     = view.findViewById<TextView>(R.id.tvWordAnkiHeadword)
        val tvReading      = view.findViewById<TextView>(R.id.tvWordAnkiReading)
        val tvPos          = view.findViewById<TextView>(R.id.tvWordAnkiPos)
        val etDefinition   = view.findViewById<EditText>(R.id.etWordAnkiDefinition)
        val tvPhotoLabel   = view.findViewById<TextView>(R.id.tvWordAnkiPhotoLabel)
        val layoutPhoto    = view.findViewById<FrameLayout>(R.id.layoutWordAnkiPhoto)
        val ivPhoto        = view.findViewById<ImageView>(R.id.ivWordAnkiPhoto)
        val btnRemovePhoto = view.findViewById<Button>(R.id.btnWordAnkiRemovePhoto)
        val btnSend        = view.findViewById<Button>(R.id.btnWordAnkiSend)

        tvHeadword.text = word

        if (reading.isNotEmpty()) {
            tvReading.text = reading
            tvReading.visibility = View.VISIBLE
        }
        if (pos.isNotEmpty()) {
            tvPos.text = pos
            tvPos.visibility = View.VISIBLE
        }

        etDefinition.setText(definition)

        if (screenshotPath != null) {
            val file = File(screenshotPath)
            if (file.exists()) {
                tvPhotoLabel.visibility = View.VISIBLE
                layoutPhoto.visibility  = View.VISIBLE
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) ivPhoto.setImageBitmap(bmp)
                btnRemovePhoto.setOnClickListener {
                    includePhoto = false
                    tvPhotoLabel.visibility = View.GONE
                    layoutPhoto.visibility  = View.GONE
                }
            }
        }

        loadDecks(spinnerDeck)

        btnSend.setOnClickListener {
            val defText = etDefinition.text.toString()
            val deckId  = deckEntries.getOrNull(spinnerDeck.selectedItemPosition)?.key
                ?: Prefs(requireContext()).ankiDeckId
            if (deckId < 0L) {
                Toast.makeText(requireContext(), getString(R.string.anki_no_deck_selected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSend.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                sendToAnki(word, reading, pos, defText, deckId,
                    if (includePhoto) screenshotPath else null)
                btnSend.isEnabled = true
            }
        }
    }

    private fun loadDecks(spinner: Spinner) {
        loadAnkiDecksInto(spinner) { entries -> deckEntries = entries }
    }

    private suspend fun sendToAnki(
        word: String, reading: String, pos: String, definition: String,
        deckId: Long, screenshotPath: String?
    ) {
        val ankiManager = AnkiManager(requireContext())

        val imageFilename: String? = if (screenshotPath != null) {
            withContext(Dispatchers.IO) { ankiManager.addMediaFromFile(File(screenshotPath)) }
        } else null

        val front = buildFrontHtml(word)
        val back  = buildBackHtml(word, reading, pos, definition, imageFilename)

        val success = withContext(Dispatchers.IO) { ankiManager.addNote(deckId, front, back) }
        val msg = if (success) getString(R.string.anki_added) else getString(R.string.anki_failed)
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        if (success) dismiss()
    }

    private fun buildFrontHtml(word: String): String = buildString {
        append("<style>")
        append("body{margin:0;padding:0;}")
        append("</style>")
        append("<div class=\"gl-front\" style=\"text-align:center;font-size:2.2em;padding:32px 16px;\">$word</div>")
    }

    private fun buildBackHtml(
        word: String, reading: String, pos: String,
        definition: String, imageFilename: String?
    ): String = buildString {
        append("<style>")
        append("body{visibility:hidden!important;white-space:normal!important;}")
        append(".gl-front{display:none!important;}")
        append("#answer{display:none!important;}")
        append(".gl-back{visibility:visible!important;}")
        append("</style>")
        append("<div class=\"gl-back\">")
        if (imageFilename != null) {
            append("<div style=\"text-align:center;margin:12px 0;\">")
            append("<img src=\"$imageFilename\" style=\"max-width:100%;border-radius:6px;\">")
            append("</div>")
        }
        append("<div style=\"text-align:center;font-size:1.8em;padding:12px 4px;\">$word</div>")
        if (reading.isNotEmpty()) {
            append("<div style=\"text-align:center;font-size:1.1em;color:#888;\">${reading}</div>")
        }
        if (pos.isNotEmpty()) {
            append("<div style=\"text-align:center;font-size:0.85em;color:#888;margin-bottom:12px;\">$pos</div>")
        }
        append("<hr>")
        val defHtml = definition.lines().filter { it.isNotBlank() }
            .joinToString("<br>") { it.trimStart() }
        append("<div style=\"font-size:1.1em;margin:12px 4px;\">$defHtml</div>")
        append("</div>")
    }

    companion object {
        const val TAG = "WordAnkiReviewSheet"
        private const val ARG_WORD            = "word"
        private const val ARG_READING         = "reading"
        private const val ARG_POS             = "pos"
        private const val ARG_DEFINITION      = "definition"
        private const val ARG_SCREENSHOT_PATH = "screenshot_path"

        fun newInstance(
            word: String,
            reading: String,
            pos: String,
            definition: String,
            screenshotPath: String?
        ) = WordAnkiReviewSheet().apply {
            arguments = bundleOf(
                ARG_WORD            to word,
                ARG_READING         to reading,
                ARG_POS             to pos,
                ARG_DEFINITION      to definition,
                ARG_SCREENSHOT_PATH to screenshotPath
            )
        }
    }
}
