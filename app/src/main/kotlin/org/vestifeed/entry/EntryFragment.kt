package org.vestifeed.entry

import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Html
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ClickableSpan
import android.text.style.QuoteSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.content.ContextCompat
import androidx.core.text.parseAsHtml
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.iterator
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonParser
import org.vestifeed.parser.AtomLinkRel
import org.vestifeed.dialog.showErrorDialog
import org.vestifeed.enclosures.EnclosuresAdapter
import org.vestifeed.enclosures.PlaybackState
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.vestifeed.R
import org.vestifeed.app.db
import org.vestifeed.app.sync
import org.vestifeed.databinding.FragmentEntryBinding
import org.vestifeed.db.table.EntryTable
import org.vestifeed.db.table.LinkTable
import org.vestifeed.enclosures.EnclosuresRepo
import org.vestifeed.feedsettings.FeedSettingsFragment
import org.vestifeed.navigation.AppFragment
import org.vestifeed.navigation.openUrl
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class EntryFragment : AppFragment() {

    private val entryId by lazy { requireArguments().getString("entryId", "") }

    private val conf by lazy { requireContext().db().conf.select() }

    private var _binding: FragmentEntryBinding? = null
    private val binding get() = _binding!!

    private val enclosuresAdapter = createEnclosuresAdapter()

    private val anchorIndex = mutableMapOf<String, AnchorTarget>()

    private val findInPageMatches = mutableListOf<FindInPageMatch>()
    private val findInPageSpans = mutableListOf<BackgroundColorSpan>()
    private var currentMatchSpan: BackgroundColorSpan? = null
    private var currentMatchIndex: Int = -1
    private var isSearchBarVisible: Boolean = false

    private var mediaPlayer: MediaPlayer? = null
    private var currentPlaybackUri: Uri? = null
    private var currentPlaybackState: PlaybackState = PlaybackState.Idle

    private val highlightColor: Int by lazy {
        ContextCompat.getColor(requireContext(), R.color.find_in_page_highlight)
    }
    private val currentMatchColor: Int by lazy {
        ContextCompat.getColor(requireContext(), R.color.find_in_page_current_match)
    }

    private val searchTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            applyFindInPage(s?.toString().orEmpty())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentEntryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            if (parentFragment is UnreadPagerFragment) {
                requireActivity().supportFragmentManager.popBackStack()
            } else {
                parentFragmentManager.popBackStack()
            }
        }

        binding.closeSearchButton.setOnClickListener { closeFindInPage() }
        binding.previousMatchButton.setOnClickListener { navigateFindInPageMatch(-1) }
        binding.nextMatchButton.setOnClickListener { navigateFindInPageMatch(1) }
        binding.searchInput.addTextChangedListener(searchTextWatcher)
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                navigateFindInPageMatch(1)
                true
            } else {
                false
            }
        }

        binding.enclosures.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = enclosuresAdapter
            addItemDecoration(CardListAdapterDecoration(resources.getDimensionPixelSize(R.dimen.dp_16)))
        }

        lifecycleScope.launch {
            EnclosuresRepo(requireContext(), db()).deletePartialDownloads()
        }

        loadEntry()

        binding.apply {
            scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
                if (scrollView.canScrollVertically(1)) {
                    fab.show()
                } else {
                    fab.hide()
                }
            }

            ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).let {
                    v.updatePadding(top = it.top)
                }
                insets
            }

            ViewCompat.setOnApplyWindowInsetsListener(scrollView) { v, insets ->
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).let {
                    v.updatePadding(bottom = it.bottom)
                }
                insets
            }

            ViewCompat.setOnApplyWindowInsetsListener(fab) { v, insets ->
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).let {
                    v.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                        bottomMargin = it.bottom + resources.getDimensionPixelSize(R.dimen.dp_16)
                    }
                }
                insets
            }
        }
    }

    override fun onPause() {
        releaseMediaPlayer()
        super.onPause()
    }

    override fun onDestroyView() {
        releaseMediaPlayer()
        clearFindInPageHighlights()
        binding.searchInput.removeTextChangedListener(searchTextWatcher)
        super.onDestroyView()
        _binding = null
    }

    private fun toggleFindInPage() {
        if (isSearchBarVisible) closeFindInPage() else openFindInPage()
    }

    private fun openFindInPage() {
        binding.searchBar.isVisible = true
        isSearchBarVisible = true
        binding.searchInput.requestFocus()
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(binding.searchInput, 0)
    }

    private fun closeFindInPage() {
        if (binding.searchInput.text?.isNotEmpty() == true) {
            binding.searchInput.setText("")
        }
        binding.searchInput.clearFocus()
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchBar.isVisible = false
        isSearchBarVisible = false
    }

    private fun applyFindInPage(query: String) {
        clearFindInPageHighlights()
        if (query.isEmpty()) {
            updateFindInPageCounter()
            return
        }
        collectFindInPageTextViews().forEach { view ->
            val spannable = ensureMutableText(view)
            val source = spannable.toString()
            var index = 0
            while (index < source.length) {
                val found = source.indexOf(query, index, ignoreCase = true)
                if (found < 0) break
                val end = found + query.length
                val span = BackgroundColorSpan(highlightColor)
                spannable.setSpan(span, found, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                findInPageSpans.add(span)
                findInPageMatches.add(FindInPageMatch(view, found, end))
                index = end
            }
        }
        if (findInPageMatches.isNotEmpty()) {
            currentMatchIndex = 0
            highlightCurrentFindInPageMatch()
        }
        updateFindInPageCounter()
    }

    private fun navigateFindInPageMatch(direction: Int) {
        if (findInPageMatches.isEmpty()) return
        val size = findInPageMatches.size
        currentMatchIndex = ((currentMatchIndex + direction) % size + size) % size
        highlightCurrentFindInPageMatch()
        updateFindInPageCounter()
    }

    private fun highlightCurrentFindInPageMatch() {
        currentMatchSpan?.let { span ->
            val view = findInPageMatches.getOrNull(currentMatchIndex)?.view ?: return@let
            (view.text as? Spannable)?.removeSpan(span)
        }
        val match = findInPageMatches.getOrNull(currentMatchIndex) ?: return
        val span = BackgroundColorSpan(currentMatchColor)
        (match.view.text as? Spannable)?.setSpan(
            span,
            match.start,
            match.end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        currentMatchSpan = span
        scrollToFindInPageMatch(match)
    }

    private fun clearFindInPageHighlights() {
        findInPageMatches.forEach { match ->
            val spannable = match.view.text as? Spannable ?: return@forEach
            findInPageSpans.forEach { spannable.removeSpan(it) }
            currentMatchSpan?.let { spannable.removeSpan(it) }
        }
        findInPageMatches.clear()
        findInPageSpans.clear()
        currentMatchSpan = null
        currentMatchIndex = -1
        updateFindInPageCounter()
    }

    private fun updateFindInPageCounter() {
        binding.matchCounter.text = if (findInPageMatches.isEmpty()) {
            getString(R.string.find_match_n_of_n, 0, 0)
        } else {
            getString(
                R.string.find_match_n_of_n,
                currentMatchIndex + 1,
                findInPageMatches.size,
            )
        }
        binding.previousMatchButton.isEnabled = findInPageMatches.isNotEmpty()
        binding.nextMatchButton.isEnabled = findInPageMatches.isNotEmpty()
    }

    private fun scrollToFindInPageMatch(match: FindInPageMatch) {
        val layout = match.view.layout ?: return
        val line = layout.getLineForOffset(match.start)
        val rect = Rect(0, layout.getLineTop(line), match.view.width, layout.getLineBottom(line))
        match.view.requestRectangleOnScreen(rect, false)
    }

    private fun scrollToAnchor(view: TextView, offset: Int) {
        val layout = view.layout ?: return
        val text = view.text ?: return
        if (offset < 0 || offset >= text.length) return
        val line = layout.getLineForOffset(offset)
        val lineTop = layout.getLineTop(line)

        var parent: ViewParent? = view.parent
        var scrollView: android.widget.ScrollView? = null
        while (parent != null) {
            if (parent is android.widget.ScrollView) {
                scrollView = parent
                break
            }
            parent = parent.parent
        }

        if (scrollView == null) {
            view.requestRectangleOnScreen(
                Rect(0, lineTop, view.width, layout.getLineBottom(line)),
                false,
            )
            return
        }

        var yInScrollChild = lineTop
        var node: View = view
        while (node.parent is View && node.parent !== scrollView) {
            node = node.parent as View
            yInScrollChild += node.top
        }
        val desiredScrollY = (yInScrollChild - resources.getDimensionPixelSize(R.dimen.dp_16))
            .coerceAtLeast(0)
        scrollView.smoothScrollTo(0, desiredScrollY)
    }

    private fun collectFindInPageTextViews(): List<TextView> {
        val views = mutableListOf<TextView>()
        views.add(binding.title)
        for (i in 0 until binding.summaryView.childCount) {
            val child = binding.summaryView.getChildAt(i)
            when (child) {
                is TextView -> views.add(child)
                is ViewGroup -> (child.getChildAt(0) as? TextView)?.let { views.add(it) }
            }
        }
        return views
    }

    private fun ensureMutableText(textView: TextView): SpannableStringBuilder {
        val current = textView.text
        if (current is SpannableStringBuilder) return current
        val builder = SpannableStringBuilder(current)
        textView.text = builder
        return builder
    }

    private data class FindInPageMatch(val view: TextView, val start: Int, val end: Int)

    private fun loadEntry() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val entry = db().entry.selectById(entryId)
                if (entry == null) {
                    showError(
                        getString(
                            R.string.cannot_find_entry_with_id_s,
                            entryId
                        )
                    ) { popBackStack() }
                    return@launch
                }

                val feed = db().feed.selectById(entry.feedId)
                if (feed == null) {
                    showError(
                        getString(
                            R.string.cannot_find_feed_with_id_s,
                            entry.feedId
                        )
                    ) { popBackStack() }
                    return@launch
                }

                val links = db().link.selectByEntryId(entryId)
                showEntry(feed.title, entry, links)
            }.onFailure {
                showError(it.message ?: "") { popBackStack() }
            }
        }
    }

    private fun showEntry(feedTitle: String, entry: EntryTable.Entry, entryLinks: List<LinkTable.Link>) {
        val menu = binding.toolbar.menu

        menu.findItem(R.id.toggleBookmarked)?.isVisible = true
        menu.findItem(R.id.comments)?.apply {
            isVisible = entry.extCommentsUrl.isNotBlank()
            setOnMenuItemClickListener {
                openUrl(entry.extCommentsUrl, conf.useBuiltInBrowser)
                true
            }
        }
        menu.findItem(R.id.feedSettings)?.isVisible = true
        menu.findItem(R.id.share)?.isVisible = true
        menu.findItem(R.id.findInPage)?.isVisible = true
        menu.findItem(R.id.ogFetchLog)?.isVisible = true

        binding.contentContainer.isVisible = true
        binding.toolbar.title = feedTitle

        binding.toolbar.setOnMenuItemClickListener { onMenuItemClick(it, entry, entryLinks) }

        updateBookmarkedButton(entry.extBookmarked)
        binding.title.text = entry.title
        binding.date.text =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).format(entry.published)

        val baseUrl = entryLinks
            .firstOrNull { it.rel is AtomLinkRel.Alternate && it.type == "text/html" }
            ?.href
            ?.toHttpUrlOrNull()

        renderEntryContent(entry.contentText ?: "", baseUrl)
        binding.progress.isVisible = false

        enclosuresAdapter.submitList(
            buildEnclosureItems(entry, entryLinks)
        )

        val firstHtmlLink = entryLinks
            .firstOrNull { it.rel is AtomLinkRel.Alternate && it.type == "text/html" }

        if (firstHtmlLink != null) {
            binding.fab.show()
            binding.fab.setOnClickListener {
                openUrl(firstHtmlLink.href, conf.useBuiltInBrowser)
            }
        }
    }

    private fun showError(message: String, action: () -> Unit) {
        binding.toolbar.menu.iterator().forEach { it.isVisible = false }
        binding.contentContainer.isVisible = false
        showErrorDialog(message, DialogInterface.OnDismissListener { action() })
    }

    private fun popBackStack() {
        parentFragmentManager.popBackStack()
    }

    private fun onMenuItemClick(
        menuItem: MenuItem?,
        entry: EntryTable.Entry,
        entryLinks: List<LinkTable.Link>
    ): Boolean {
        when (menuItem?.itemId) {
            R.id.toggleBookmarked -> {
                lifecycleScope.launch {
                    val newBookmarkedState = !entry.extBookmarked
                    db().entry.updateBookmarkedAndBookmarkedSynced(
                        id = entry.id,
                        extBookmarked = newBookmarkedState,
                        extBookmarkedSynced = false,
                    )
                    updateBookmarkedButton(newBookmarkedState)
                    sync().runInBackground()
                }
                return true
            }

            R.id.feedSettings -> {
                val fragmentManager = if (parentFragment is UnreadPagerFragment) {
                    requireActivity().supportFragmentManager
                } else {
                    parentFragmentManager
                }
                fragmentManager.commit {
                    replace(
                        R.id.fragmentContainerView,
                        FeedSettingsFragment::class.java,
                        bundleOf("feedId" to entry.feedId),
                    )
                    addToBackStack(null)
                }
                return true
            }

            R.id.share -> {
                val firstAlternateLink =
                    entryLinks.firstOrNull { it.rel is AtomLinkRel.Alternate } ?: return true
                val intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, entry.title)
                    putExtra(Intent.EXTRA_TEXT, firstAlternateLink.href.toString())
                }
                startActivity(Intent.createChooser(intent, ""))
                return true
            }

            R.id.findInPage -> {
                toggleFindInPage()
                return true
            }

            R.id.ogFetchLog -> {
                showOgFetchLog(entry.extOpenGraphImageLog)
                return true
            }
        }
        return false
    }

    private fun showOgFetchLog(rawJson: String) {
        val rendered = formatOgLog(rawJson)
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_og_log, null, false)
        dialogView.findViewById<TextView>(R.id.ogLogText).text = rendered

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.og_image_fetch_log)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatOgLog(rawJson: String): CharSequence {
        val array = runCatching {
            JsonParser.parseString(rawJson).asJsonArray
        }.getOrNull() ?: return getString(R.string.og_image_fetch_log_empty)

        if (array.size() == 0) {
            return getString(R.string.og_image_fetch_log_empty)
        }

        val builder = StringBuilder()
        for (i in 0 until array.size()) {
            val entry = array.get(i).asJsonObject
            val timestamp = entry.get("timestamp")?.asString.orEmpty()
            val message = entry.get("message")?.asString.orEmpty()
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(timestamp).append(" - ").append(message)
        }
        return builder
    }

    private fun updateBookmarkedButton(bookmarked: Boolean) {
        binding.toolbar.menu?.findItem(R.id.toggleBookmarked)?.apply {
            if (bookmarked) {
                setIcon(R.drawable.ic_baseline_bookmark_24)
                setTitle(R.string.remove_bookmark)
            } else {
                setIcon(R.drawable.ic_baseline_bookmark_border_24)
                setTitle(R.string.bookmark)
            }
        }
    }

    fun downloadAudioEnclosure(enclosure: LinkTable.Link) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                EnclosuresRepo(requireContext(), db()).downloadAudioEnclosure(enclosure)
                Toast.makeText(requireContext(), "Downloaded", Toast.LENGTH_LONG).show()
                refreshEnclosures()
            } catch (e: Exception) {
                showErrorDialog(e)
            }
        }
    }

    private fun refreshEnclosures() {
        val entry = db().entry.selectById(entryId) ?: return
        val links = db().link.selectByEntryId(entryId)

        enclosuresAdapter.submitList(buildEnclosureItems(entry, links))
    }

    private fun buildEnclosureItems(
        entry: EntryTable.Entry,
        links: List<LinkTable.Link>,
    ): List<EnclosuresAdapter.Item> {
        return links
            .filter { it.rel is AtomLinkRel.Enclosure }
            .filter { it.type?.startsWith("audio") ?: false }
            .mapIndexed { index, enclosure ->
                EnclosuresAdapter.Item(
                    entryId = entry.id,
                    enclosure = enclosure,
                    primaryText = getString(R.string.audio_n, index + 1),
                    secondaryText = enclosure.href.toString(),
                    playbackState = playbackStateFor(enclosure),
                )
            }
    }

    private fun playbackStateFor(enclosure: LinkTable.Link): PlaybackState {
        if (currentPlaybackUri != enclosure.extCacheUri?.toUri()) return PlaybackState.Idle
        return currentPlaybackState
    }

    fun playAudioEnclosure(enclosure: LinkTable.Link) {
        togglePlaybackFor(enclosure)
    }

    private fun togglePlaybackFor(enclosure: LinkTable.Link) {
        val cacheUri = enclosure.extCacheUri?.toUri()
        if (cacheUri == null || !conf.useBuiltInAudioPlayer) {
            launchExternalAudioPlayer(enclosure)
            return
        }

        val player = mediaPlayer
        if (player != null && currentPlaybackUri == cacheUri) {
            when (currentPlaybackState) {
                PlaybackState.Playing -> {
                    runCatching { player.pause() }
                    setPlaybackState(cacheUri, PlaybackState.Paused)
                }
                PlaybackState.Paused -> {
                    runCatching { player.start() }
                    setPlaybackState(cacheUri, PlaybackState.Playing)
                }
                PlaybackState.Idle -> Unit
            }
        } else {
            startPlaybackFor(cacheUri)
        }
    }

    private fun startPlaybackFor(uri: Uri) {
        releaseMediaPlayer()
        val player = MediaPlayer()
        mediaPlayer = player
        currentPlaybackUri = uri
        currentPlaybackState = PlaybackState.Playing
        runCatching {
            player.setDataSource(requireContext(), uri)
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener {
                if (mediaPlayer === it) {
                    mediaPlayer = null
                    currentPlaybackUri = null
                    currentPlaybackState = PlaybackState.Idle
                    refreshEnclosures()
                } else {
                    runCatching { it.release() }
                }
            }
            player.setOnErrorListener { mp, _, _ ->
                if (mediaPlayer === mp) {
                    mediaPlayer = null
                    currentPlaybackUri = null
                    currentPlaybackState = PlaybackState.Idle
                }
                runCatching { mp.release() }
                showErrorDialog(getString(R.string.could_not_play_audio))
                refreshEnclosures()
                true
            }
            player.prepareAsync()
        }.onFailure {
            if (mediaPlayer === player) {
                mediaPlayer = null
                currentPlaybackUri = null
                currentPlaybackState = PlaybackState.Idle
            }
            runCatching { player.release() }
            showErrorDialog(it)
            refreshEnclosures()
        }
        refreshEnclosures()
    }

    private fun setPlaybackState(uri: Uri, state: PlaybackState) {
        currentPlaybackState = state
        refreshEnclosures()
    }

    private fun launchExternalAudioPlayer(enclosure: LinkTable.Link) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(enclosure.extCacheUri!!.toUri(), enclosure.type)

        runCatching {
            startActivity(intent)
        }.onFailure {
            if (it is ActivityNotFoundException) {
                showErrorDialog(getString(R.string.you_have_no_apps_which_can_play_this_podcast))
            } else {
                showErrorDialog(it)
            }
        }
    }

    private fun releaseMediaPlayer() {
        val player = mediaPlayer ?: return
        mediaPlayer = null
        currentPlaybackUri = null
        currentPlaybackState = PlaybackState.Idle
        runCatching { if (player.isPlaying) player.stop() }
        runCatching { player.release() }
    }

    private fun deleteEnclosure(enclosure: LinkTable.Link) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                EnclosuresRepo(requireContext(), db()).deleteFromCache(enclosure)
                refreshEnclosures()
            } catch (e: Exception) {
                showErrorDialog(e)
            }
        }
    }

    private fun renderEntryContent(content: String, baseUrl: HttpUrl?) {
        binding.summaryView.removeAllViews()
        anchorIndex.clear()

        splitEntryContent(content).forEach { block ->
            when (block) {
                is EntryContentBlock.Markup -> addMarkupBlock(block.content, baseUrl)
                is EntryContentBlock.Preformatted -> addPreformattedBlock(block.content)
            }
        }
    }

    private fun addMarkupBlock(content: String, baseUrl: HttpUrl?) {
        val textView = createEntryTextView()
        val parsedContent = parseEntryContent(
            content,
            TextViewImageGetter(
                textView = textView,
                scope = viewLifecycleOwner.lifecycleScope,
                baseUrl = baseUrl,
            ),
        )
        if (parsedContent.isBlank()) return

        parsedContent.applyStyle(textView)
        textView.text = parsedContent
        textView.movementMethod = LinkMovementMethod.getInstance()
        binding.summaryView.addView(textView)
        indexHeadings(content, textView)
    }

    private fun indexHeadings(html: String, textView: TextView) {
        val rendered = textView.text.toString()
        if (rendered.isEmpty()) return

        findHeadingReferences(html).forEach { ref ->
            if (anchorIndex.containsKey(ref.id)) return@forEach
            val offset = rendered.lastIndexOf(ref.text)
            if (offset < 0) return@forEach
            anchorIndex[ref.id] = AnchorTarget(textView, offset)
        }
    }

    private fun addPreformattedBlock(content: String) {
        if (content.isBlank()) return

        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.dp_16)
        val verticalPadding = resources.getDimensionPixelSize(R.dimen.dp_8)
        val textView = createEntryTextView().apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setHorizontallyScrolling(true)
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            text = content
            typeface = Typeface.MONOSPACE
        }
        val scrollView = HorizontalScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            isFillViewport = true
            addView(textView)
        }
        binding.summaryView.addView(scrollView)
    }

    private fun createEntryTextView() = TextView(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val textAppearance = TypedValue()
        requireContext().theme.resolveAttribute(
            com.google.android.material.R.attr.textAppearanceBody1,
            textAppearance,
            true,
        )
        setTextAppearance(textAppearance.resourceId)
        setLineSpacing(0f, 1.2f)
        setPadding(
            resources.getDimensionPixelSize(R.dimen.dp_16),
            0,
            resources.getDimensionPixelSize(R.dimen.dp_16),
            0,
        )
        setTextSize(TypedValue.COMPLEX_UNIT_SP, conf.entryBodyFontSize.toFloat())
        setTextIsSelectable(true)
    }

    private fun SpannableStringBuilder.applyStyle(textView: TextView) {
        val spans = getSpans(0, length - 1, Any::class.java)

        spans.forEach {
            when (it) {
                is BulletSpan -> {
                    val radius = resources.getDimension(R.dimen.bullet_radius).toInt()
                    val gap = resources.getDimension(R.dimen.bullet_gap).toInt()

                    setSpan(
                        BulletSpan(gap, textView.currentTextColor, radius),
                        getSpanStart(it),
                        getSpanEnd(it),
                        0
                    )
                    removeSpan(it)
                }

                is QuoteSpan -> {
                    val color = binding.date.currentTextColor
                    val stripe = resources.getDimension(R.dimen.quote_stripe_width).toInt()
                    val gap = resources.getDimension(R.dimen.quote_gap).toInt()

                    setSpan(
                        QuoteSpan(color, stripe, gap),
                        getSpanStart(it),
                        getSpanEnd(it),
                        0
                    )
                    removeSpan(it)
                }

                is URLSpan -> {
                    if (it.url.startsWith("#")) {
                        val id = it.url.substring(1)
                        if (id.isEmpty()) {
                            removeSpan(it)
                        } else {
                            val start = getSpanStart(it)
                            val end = getSpanEnd(it)
                            removeSpan(it)
                            setSpan(
                                AnchorClickSpan(
                                    anchorId = id,
                                    onScroll = ::scrollToAnchor,
                                    getTarget = { anchorIndex[id] },
                                ),
                                start,
                                end,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun parseEntryContent(
        content: String,
        imageGetter: Html.ImageGetter
    ): SpannableStringBuilder {
        val summary = content.parseAsHtml(
            imageGetter = imageGetter,
        ) as SpannableStringBuilder

        if (summary.isBlank()) return summary

        while (summary.contains("\n\n\n")) {
            val index = summary.indexOf("\n\n\n")
            summary.delete(index, index + 1)
        }

        while (summary.startsWith("\n\n")) {
            summary.delete(0, 1)
        }

        while (summary.endsWith("\n\n")) {
            summary.delete(summary.length - 2, summary.length - 1)
        }

        return summary
    }

    private fun createEnclosuresAdapter() = EnclosuresAdapter(object : EnclosuresAdapter.Callback {
        override fun onDownloadClick(item: EnclosuresAdapter.Item) =
            downloadAudioEnclosure(item.enclosure)

        override fun onPlayPauseClick(item: EnclosuresAdapter.Item) =
            togglePlaybackFor(item.enclosure)
        override fun onDeleteClick(item: EnclosuresAdapter.Item) = deleteEnclosure(item.enclosure)
    })

    private class CardListAdapterDecoration(private val gapInPixels: Int) :
        RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            val bottomGap = if (position == (parent.adapter?.itemCount ?: 0) - 1) gapInPixels else 0
            outRect.set(gapInPixels, gapInPixels, gapInPixels, bottomGap)
        }
    }
}

private data class AnchorTarget(val view: TextView, val offset: Int)

private class AnchorClickSpan(
    private val anchorId: String,
    private val onScroll: (TextView, Int) -> Unit,
    private val getTarget: () -> AnchorTarget?,
) : ClickableSpan() {
    override fun onClick(widget: View) {
        val target = getTarget() ?: return
        onScroll(target.view, target.offset)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = ds.linkColor
        ds.isUnderlineText = true
    }
}

internal sealed interface EntryContentBlock {
    data class Markup(val content: String) : EntryContentBlock
    data class Preformatted(val content: String) : EntryContentBlock
}

internal data class HeadingReference(val id: String, val text: String)

private val headingSelector = "h1[id], h2[id], h3[id], h4[id], h5[id], h6[id]"

internal fun findHeadingReferences(html: String): List<HeadingReference> {
    return Jsoup.parseBodyFragment(html)
        .body()
        .select(headingSelector)
        .mapNotNull { heading ->
            val id = heading.id()
            val text = heading.text()
            if (id.isEmpty() || text.isEmpty()) null else HeadingReference(id, text)
        }
}

private val preformattedBlockRegex = Regex("""(?is)<pre\b[^>]*>.*?</pre\s*>""")

internal fun splitEntryContent(content: String): List<EntryContentBlock> {
    val blocks = mutableListOf<EntryContentBlock>()
    var markupStart = 0

    preformattedBlockRegex.findAll(content).forEach { match ->
        if (match.range.first > markupStart) {
            blocks += EntryContentBlock.Markup(content.substring(markupStart, match.range.first))
        }

        val preformatted = Jsoup.parseBodyFragment(match.value)
            .selectFirst("pre")
            ?.wholeText()
            ?.trim('\r', '\n')
            .orEmpty()
        blocks += EntryContentBlock.Preformatted(preformatted)
        markupStart = match.range.last + 1
    }

    if (markupStart < content.length) {
        blocks += EntryContentBlock.Markup(content.substring(markupStart))
    }

    if (blocks.isEmpty()) {
        blocks += EntryContentBlock.Markup(content)
    }

    return blocks
}
