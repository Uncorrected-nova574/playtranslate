package com.gamelens.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gamelens.AnkiManager
import com.gamelens.CaptureService
import com.gamelens.Prefs
import com.gamelens.R
import com.gamelens.TranslationManager
import com.gamelens.dictionary.Deinflector
import com.gamelens.dictionary.DictionaryManager
import com.gamelens.model.TranslationResult
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Shared fragment that displays translation results: original text, translation,
 * word lookups, copy/Anki buttons. Used by both MainActivity and TranslationResultActivity.
 */
class TranslationResultFragment : Fragment() {

    /**
     * Host interface for activities that embed this fragment.
     */
    interface TranslationResultHost {
        fun getCaptureService(): CaptureService?
        fun onWordTapped(
            word: String,
            screenshotPath: String?,
            sentenceOriginal: String?,
            sentenceTranslation: String?,
            wordResults: Map<String, Triple<String, String, Int>>
        )
        fun onInteraction()
        fun getAnkiPermissionLauncher(): androidx.activity.result.ActivityResultLauncher<String>?
    }

    // ── Views ─────────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusHint: TextView
    private lateinit var tvLiveHint: TextView
    private lateinit var statusContainer: View
    private lateinit var resultsContent: ScrollView
    private lateinit var tvOriginal: ClickableTextView
    private lateinit var tvTranslation: TextView
    private lateinit var tvTranslationNote: TextView
    private lateinit var tvMainWordsLoading: TextView
    private lateinit var mainWordsContainer: LinearLayout
    private lateinit var btnCopyOriginal: ImageButton
    private lateinit var btnCopyTranslation: ImageButton
    private lateinit var btnToggleTranslation: ImageButton
    private lateinit var btnToggleOriginal: ImageButton
    private lateinit var btnToggleWords: ImageButton
    private lateinit var translationContent: LinearLayout
    private lateinit var originalContent: LinearLayout
    private lateinit var wordsContent: LinearLayout
    private lateinit var labelOriginal: TextView
    private lateinit var labelTranslation: TextView
    private lateinit var tvNoWords: TextView
    private lateinit var tvTransliteration: TextView

    private var wordLookupJob: Job? = null
    val mainWordResults = mutableMapOf<String, Triple<String, String, Int>>()
    var lastResult: TranslationResult? = null
        private set
    private var editTranslationManager: TranslationManager? = null

    /** Called when Anki button enabled state changes (e.g. after word lookups complete). */
    var onAnkiEnabledChanged: ((Boolean) -> Unit)? = null

    private val romajiTransliterator by lazy {
        try { android.icu.text.Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC") }
        catch (_: Exception) { null }
    }

    private val host: TranslationResultHost?
        get() = activity as? TranslationResultHost

    private val prefs: Prefs by lazy { Prefs(requireContext()) }

    // ── Fragment lifecycle ─────────────────────────────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_translation_result, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupButtons()
    }

    override fun onDestroyView() {
        wordLookupJob?.cancel()
        editTranslationManager?.close()
        super.onDestroyView()
    }

    private fun bindViews(view: View) {
        tvStatus             = view.findViewById(R.id.tvStatus)
        tvStatusHint         = view.findViewById(R.id.tvStatusHint)
        tvLiveHint           = view.findViewById(R.id.tvLiveHint)
        statusContainer      = view.findViewById(R.id.statusContainer)
        resultsContent       = view.findViewById(R.id.resultsContent)
        tvOriginal           = view.findViewById(R.id.tvOriginal)
        tvTranslation        = view.findViewById(R.id.tvTranslation)
        tvTranslationNote    = view.findViewById(R.id.tvTranslationNote)
        tvMainWordsLoading   = view.findViewById(R.id.tvMainWordsLoading)
        mainWordsContainer   = view.findViewById(R.id.mainWordsContainer)
        btnCopyOriginal      = view.findViewById(R.id.btnCopyOriginal)
        btnCopyTranslation   = view.findViewById(R.id.btnCopyTranslation)
        btnToggleTranslation = view.findViewById(R.id.btnToggleTranslation)
        btnToggleOriginal    = view.findViewById(R.id.btnToggleOriginal)
        btnToggleWords       = view.findViewById(R.id.btnToggleWords)
        translationContent   = view.findViewById(R.id.translationContent)
        originalContent      = view.findViewById(R.id.originalContent)
        wordsContent         = view.findViewById(R.id.wordsContent)
        labelOriginal        = view.findViewById(R.id.labelOriginal)
        labelTranslation     = view.findViewById(R.id.labelTranslation)
        tvNoWords            = view.findViewById(R.id.tvNoWords)
        tvTransliteration    = view.findViewById(R.id.tvTransliteration)
    }

    private fun setupButtons() {
        btnCopyOriginal.setOnClickListener {
            copyToClipboard(tvOriginal.text?.toString() ?: return@setOnClickListener)
        }
        btnCopyTranslation.setOnClickListener {
            copyToClipboard(tvTranslation.text?.toString() ?: return@setOnClickListener)
        }
        btnToggleTranslation.setOnClickListener {
            prefs.hideTranslationSection = !prefs.hideTranslationSection
            applyTranslationVisibility()
        }
        btnToggleOriginal.setOnClickListener {
            prefs.hideOriginalSection = !prefs.hideOriginalSection
            applyOriginalVisibility()
        }
        btnToggleWords.setOnClickListener {
            prefs.hideWordsSection = !prefs.hideWordsSection
            applyWordsVisibility()
        }
    }

    private fun applyTranslationVisibility() {
        val hidden = prefs.hideTranslationSection
        translationContent.visibility = if (hidden) View.GONE else View.VISIBLE
        btnCopyTranslation.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        btnToggleTranslation.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
    }

    private fun applyOriginalVisibility() {
        val hidden = prefs.hideOriginalSection
        originalContent.visibility = if (hidden) View.GONE else View.VISIBLE
        btnCopyOriginal.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        btnToggleOriginal.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
    }

    private fun applyWordsVisibility() {
        val hidden = prefs.hideWordsSection
        wordsContent.visibility = if (hidden) View.GONE else View.VISIBLE
        btnToggleWords.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun displayResult(result: TranslationResult) {
        if (!isAdded || view == null) return
        lastResult = result
        tvOriginal.setSegments(result.segments)
        tvOriginal.onTapAtOffset = { offset -> host?.onInteraction(); onOriginalTapped(offset) }
        tvTranslation.text = result.translatedText
        tvTranslationNote.text = result.note ?: ""
        tvTranslationNote.visibility = if (result.note != null) View.VISIBLE else View.GONE
        applyTranslationVisibility()
        applyOriginalVisibility()
        applyWordsVisibility()
        labelOriginal.text    = langDisplayName(selectedSourceLang())
        labelTranslation.text = langDisplayName(selectedTargetLang())
        statusContainer.visibility = View.GONE
        resultsContent.visibility  = View.VISIBLE
        resultsContent.scrollTo(0, 0)
        onAnkiEnabledChanged?.invoke(false)
        startWordLookups(result.originalText)
    }

    /** Called by the host activity when its Anki button is tapped. */
    fun onAnkiClicked() {
        host?.onInteraction()
        val result = lastResult ?: return
        val ctx = context ?: return
        val ankiManager = AnkiManager(ctx)
        when {
            !ankiManager.isAnkiDroidInstalled() ->
                Toast.makeText(ctx, getString(R.string.anki_not_installed), Toast.LENGTH_SHORT).show()
            !ankiManager.hasPermission() ->
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.anki_permission_rationale_title)
                    .setMessage(R.string.anki_permission_rationale_message)
                    .setPositiveButton(R.string.btn_continue) { _, _ ->
                        host?.getAnkiPermissionLauncher()?.launch(AnkiManager.PERMISSION)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            else ->
                AnkiReviewBottomSheet.newInstance(
                    result.originalText, result.translatedText, mainWordResults, result.screenshotPath
                ).show(childFragmentManager, AnkiReviewBottomSheet.TAG)
        }
    }

    /** Called by the host when original text is tapped (for edit overlay in MainActivity). */
    private var onOriginalTappedListener: ((Int) -> Unit)? = null

    fun setOnOriginalTappedListener(listener: (Int) -> Unit) {
        onOriginalTappedListener = listener
    }

    private fun onOriginalTapped(offset: Int) {
        onOriginalTappedListener?.invoke(offset)
    }

    fun clearResult() {
        showStatus(getString(R.string.status_idle))
    }

    fun showStatus(msg: String) {
        if (!isAdded || view == null) return
        tvStatus.text = msg
        val isIdle = msg == getString(R.string.status_idle)
        tvStatusHint.visibility = if (isIdle) View.VISIBLE else View.GONE
        tvLiveHint.visibility   = View.GONE
        statusContainer.visibility = View.VISIBLE
        resultsContent.visibility  = View.GONE
    }

    fun showError(msg: String) {
        showStatus(getString(R.string.status_error, msg))
    }

    /** Whether the results scroll view is currently visible. */
    val isShowingResults: Boolean
        get() = view != null && resultsContent.visibility == View.VISIBLE

    /** Access to the scroll view for scroll-pause detection. */
    fun getResultsScrollView(): ScrollView? = if (view != null) resultsContent else null

    fun setLiveHintText(text: CharSequence) {
        if (view != null) tvLiveHint.text = text
    }

    fun setLiveHintVisibility(visible: Boolean) {
        if (view != null) tvLiveHint.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setStatusHintVisibility(visible: Boolean) {
        if (view != null) tvStatusHint.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun getStatusText(): String = if (view != null) tvStatus.text.toString() else ""

    fun isStatusVisible(): Boolean = view != null && statusContainer.visibility == View.VISIBLE

    /** Update original text directly (for edit overlay commit). */
    fun updateOriginalText(newText: String) {
        if (view == null) return
        tvOriginal.text = newText
        tvTranslation.text = "…"
        tvTranslationNote.visibility = View.GONE
        lastResult = lastResult?.copy(originalText = newText, translatedText = "")
        startWordLookups(newText)
    }

    fun updateTranslation(translated: String) {
        if (view == null) return
        tvTranslation.text = translated
        lastResult = lastResult?.copy(translatedText = translated)
    }

    /** Show translating placeholder for drag-sentence flow. */
    fun showTranslatingPlaceholder(originalText: String, segments: List<com.gamelens.model.TextSegment>) {
        if (!isAdded || view == null) return
        tvOriginal.setSegments(segments)
        tvOriginal.onTapAtOffset = { offset -> host?.onInteraction(); onOriginalTapped(offset) }
        labelOriginal.text = langDisplayName(selectedSourceLang())
        labelTranslation.text = langDisplayName(selectedTargetLang())
        statusContainer.visibility = View.GONE
        resultsContent.visibility = View.VISIBLE
        resultsContent.scrollTo(0, 0)
        onAnkiEnabledChanged?.invoke(false)

        tvTranslation.text = getString(R.string.status_translating)
        tvTranslationNote.text = ""
        tvTranslationNote.visibility = View.GONE
        applyTranslationVisibility()
        applyOriginalVisibility()
        applyWordsVisibility()
        startWordLookups(originalText)
    }

    // ── Word lookups ──────────────────────────────────────────────────────

    fun startWordLookups(text: String) {
        wordLookupJob?.cancel()
        mainWordsContainer.removeAllViews()
        mainWordResults.clear()
        tvMainWordsLoading.visibility = View.VISIBLE
        tvMainWordsLoading.text = getString(R.string.words_loading)
        tvNoWords.visibility = View.GONE
        tvTransliteration.visibility = View.GONE

        wordLookupJob = viewLifecycleOwner.lifecycleScope.launch {
            val romajiDeferred = async { buildRomaji(text) }

            val ctx = context ?: return@launch
            val allPairs = withContext(Dispatchers.IO) {
                DictionaryManager.get(ctx.applicationContext).tokenizeWithSurfaces(text)
            }
            val seen = mutableSetOf<String>()
            val tokensWithSurface = allPairs.filter { seen.add(it.second) }
            val tokens = tokensWithSurface.map { it.second }

            if (tokens.isEmpty()) {
                tvMainWordsLoading.visibility = View.GONE
                tvNoWords.visibility = View.VISIBLE
                onAnkiEnabledChanged?.invoke(true)
                val romaji = romajiDeferred.await()
                if (romaji.isNotBlank() && romaji != text && Prefs(requireContext()).showTransliteration) {
                    tvTransliteration.text = romaji
                    tvTransliteration.visibility = View.VISIBLE
                }
                return@launch
            }

            val inflater = LayoutInflater.from(context)
            val surfaceByToken = tokensWithSurface.associate { it.second to it.first }
            val rows = tokens.map { word ->
                val row = inflater.inflate(R.layout.item_word_lookup, mainWordsContainer, false)
                row.findViewById<TextView>(R.id.tvItemWord).text = word
                row.findViewById<TextView>(R.id.tvItemMeaning).text = "…"
                mainWordsContainer.addView(row)
                Pair(word, row)
            }

            val resultsArr = arrayOfNulls<Pair<String, Triple<String, String, Int>>>(rows.size)
            val surfaceArr = arrayOfNulls<Pair<String, String>>(rows.size)

            supervisorScope {
                rows.forEachIndexed { idx, (word, row) ->
                    launch {
                        val tvWord    = row.findViewById<TextView>(R.id.tvItemWord)
                        val tvReading = row.findViewById<TextView>(R.id.tvItemReading)
                        val tvFreq    = row.findViewById<TextView>(R.id.tvItemFreq)
                        val tvMeaning = row.findViewById<TextView>(R.id.tvItemMeaning)
                        var reading = ""
                        var meaning = ""
                        var displayWord = word
                        var freqScore = 0
                        try {
                            val appCtx = context?.applicationContext ?: return@launch
                            val response = withContext(Dispatchers.IO) {
                                DictionaryManager.get(appCtx).lookup(word)
                            }
                            if (response != null && response.data.isNotEmpty()) {
                                val entry   = response.data.first()
                                val primary = entry.japanese.firstOrNull()
                                displayWord = primary?.word ?: primary?.reading ?: word
                                tvWord.text = displayWord
                                reading = primary?.reading?.takeIf { it != primary.word } ?: ""
                                freqScore = entry.freqScore
                                meaning = entry.senses.mapIndexed { i, sense ->
                                    val glosses = sense.englishDefinitions.joinToString("; ")
                                    if (entry.senses.size > 1) "${i + 1}. $glosses" else glosses
                                }.joinToString("\n")
                            }
                        } catch (_: Exception) {}
                        if (meaning.isNotEmpty()) {
                            tvReading.text = reading
                            tvMeaning.text = meaning
                            if (freqScore > 0) {
                                tvFreq.text = "★".repeat(freqScore)
                                tvFreq.visibility = View.VISIBLE
                            }
                            val lookupWord = displayWord
                            row.setOnClickListener {
                                host?.onInteraction()
                                host?.onWordTapped(
                                    lookupWord,
                                    lastResult?.screenshotPath,
                                    lastResult?.originalText,
                                    lastResult?.translatedText,
                                    mainWordResults.toMap()
                                )
                            }
                            resultsArr[idx] = Pair(displayWord, Triple(reading, meaning, freqScore))
                            val surface = surfaceByToken[word]
                            if (surface != null && surface != displayWord) {
                                surfaceArr[idx] = Pair(displayWord, surface)
                            }
                        } else {
                            mainWordsContainer.removeView(row)
                        }
                    }
                }
            }

            resultsArr.filterNotNull().forEach { (dw, rmt) ->
                mainWordResults[dw] = rmt
            }
            val surfaces = surfaceArr.filterNotNull().toMap()
            tvMainWordsLoading.visibility = View.GONE
            tvNoWords.visibility = if (mainWordResults.isEmpty()) View.VISIBLE else View.GONE
            onAnkiEnabledChanged?.invoke(true)

            LastSentenceCache.original = lastResult?.originalText
            LastSentenceCache.translation = lastResult?.translatedText
            LastSentenceCache.wordResults = mainWordResults.toMap()
            LastSentenceCache.surfaceForms = surfaces

            val romaji = romajiDeferred.await()
            if (romaji.isNotBlank() && romaji != text && Prefs(requireContext()).showTransliteration) {
                tvTransliteration.text = romaji
                tvTransliteration.visibility = View.VISIBLE
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private suspend fun buildRomaji(text: String): String = withContext(Dispatchers.IO) {
        val t = romajiTransliterator ?: return@withContext ""
        Deinflector.toKanaTokens(text).joinToString(" ") { t.transliterate(it) }
    }

    private fun selectedSourceLang() = TranslateLanguage.JAPANESE
    private fun selectedTargetLang() = TranslateLanguage.ENGLISH

    private fun langDisplayName(langCode: String): String =
        Locale(langCode).getDisplayLanguage(Locale.ENGLISH)
            .replaceFirstChar { it.uppercase() }

    private fun copyToClipboard(text: String) {
        val ctx = context ?: return
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PlayTranslate", text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }
}
