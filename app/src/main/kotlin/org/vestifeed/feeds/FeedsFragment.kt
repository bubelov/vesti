package org.vestifeed.feeds

import android.content.Context
import android.content.DialogInterface
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.vestifeed.parser.AtomLinkRel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.vestifeed.R
import org.vestifeed.app.api
import org.vestifeed.app.db
import org.vestifeed.app.sync
import org.vestifeed.backend.Miniflux
import org.vestifeed.databinding.FragmentFeedsBinding
import org.vestifeed.db.Database
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.db.table.TagTable
import org.vestifeed.dialog.showErrorDialog
import org.vestifeed.entries.EntriesFilter
import org.vestifeed.entries.EntriesFragment
import org.vestifeed.entries.toBundle
import org.vestifeed.feedsettings.FeedSettingsFragment
import org.vestifeed.navigation.AppFragment
import org.vestifeed.navigation.openUrl
import org.vestifeed.navigation.showKeyboard
import org.vestifeed.opml.OpmlDocument
import org.vestifeed.opml.OpmlOutline
import org.vestifeed.opml.OpmlVersion
import org.vestifeed.opml.leafOutlines
import org.vestifeed.opml.toOpml
import org.vestifeed.opml.toPrettyString
import org.vestifeed.opml.toXmlDocument
import org.vestifeed.settings.SettingsFragment
import org.vestifeed.tags.TagsFragment
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

class FeedsFragment : AppFragment() {
    sealed class State {
        object Loading : State()
        data class ShowingFeeds(val feeds: List<FeedsAdapter.Item>) : State()
        data class ImportingFeeds(val imported: Int, val total: Int) : State()
    }

    private val state = MutableStateFlow<State>(State.Loading)

    private var _binding: FragmentFeedsBinding? = null
    private val binding get() = _binding!!

    private val importFeedsLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }

            viewLifecycleOwner.lifecycleScope.launch {
                importOpml(requireContext().contentResolver.openInputStream(uri)!!)
            }
        }

    private val exportFeedsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
            if (uri != null) {
                exportOpml(requireContext().contentResolver.openOutputStream(uri)!!)
            }
        }

    private val exportFeedSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                exportFeedSettings(requireContext().contentResolver.openOutputStream(uri)!!)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFeedsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, insets ->
            insets.getInsets(WindowInsetsCompat.Type.statusBars()).let {
                v.updatePadding(top = it.top)
            }
            insets
        }

        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.manageTags -> openTagsManager()
                R.id.importFeeds -> importFeedsLauncher.launch("*/*")
                R.id.exportFeeds -> exportFeedsLauncher.launch("feeds.opml")
                R.id.exportFeedSettings -> exportFeedSettingsLauncher.launch("feed-settings.json")
                R.id.settings -> {
                    parentFragmentManager.commit {
                        replace(R.id.fragmentContainerView, SettingsFragment::class.java, null)
                        addToBackStack(null)
                    }
                    true
                }
            }

            true
        }

        binding.list.apply {
            setHasFixedSize(true)
            adapter = createFeedsAdapter()
            layoutManager = LinearLayoutManager(requireContext())
            addItemDecoration(ListItemDecoration(requireContext()))

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (canScrollVertically(1) || !canScrollVertically(-1)) {
                        binding.fab.show()
                    } else {
                        binding.fab.hide()
                    }
                }
            })
        }

        binding.importOpml.setOnClickListener { importFeedsLauncher.launch("*/*") }

        binding.fab.setOnClickListener { showAddFeedDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            state.update { State.Loading }
            val feeds = withContext(Dispatchers.IO) {
                db().feed.selectAll()
            }
            state.update { State.ShowingFeeds(feeds.map { it.toItem(db()) }) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                state.collect { binding.setState(it) }
            }
        }

        handleAddFeedIntent()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Snap the feeds list back to position 0. Invoked by the hosting
     * activity when the user re-taps the Feeds bottom-nav entry while it's
     * already active — a "scroll to top" gesture mirroring the Unread and
     * Bookmarks tabs. Any in-progress fling is cancelled first so a list
     * still coasting from a recent swipe doesn't keep scrolling after we
     * re-anchor at position 0.
     */
    fun scrollToTop() {
        val list = _binding?.list ?: return
        list.stopScroll()
        (list.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(0, 0)
    }

    private suspend fun importOpml(document: InputStream) {
        val outlines = try {
            withContext(Dispatchers.IO) {
                DocumentBuilderFactory
                    .newInstance()
                    .newDocumentBuilder()
                    .parse(document)
                    .toOpml()
                    .leafOutlines()
            }
        } catch (e: Throwable) {
            showErrorDialog(e)
            return
        }

        state.update { State.ImportingFeeds(0, outlines.size) }

        var feedsImported = 0
        var feedsExisted = 0
        var feedsFailed = 0
        val errors = mutableListOf<String>()

        val existingFeedIds = withContext(Dispatchers.IO) {
            db().feed.selectAll().map { it.id }
        }

        val existingLinks = withContext(Dispatchers.IO) {
            existingFeedIds.flatMap { db().link.selectByFeedId(it) }
        }

        val feedUrls = outlines.mapNotNull { it.xmlUrl?.toHttpUrlOrNull() }
        if (!requestLocalNetworkAccess(feedUrls)) {
            showErrorDialog(R.string.local_network_permission_required)
            return
        }

        val isMiniflux = db().conf.select().backend == ConfTable.Backend.Miniflux
        val importCategoryId: Long? = if (isMiniflux) {
            val title = "OPML Import ${LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)}"
            try {
                val miniflux = api() as? Miniflux
                if (miniflux == null) {
                    showErrorDialog(Exception("Miniflux backend expected but was not active"))
                    return
                }
                withContext(Dispatchers.IO) { miniflux.findOrCreateCategory(title) }.id
            } catch (e: Throwable) {
                showErrorDialog(e)
                return
            }
        } else {
            null
        }

        for (outline in outlines) {
            val outlineUrl = (outline.xmlUrl ?: "").toHttpUrlOrNull()

            if (outlineUrl == null) {
                errors += "Invalid URL: ${outline.xmlUrl}"
                feedsFailed++
                continue
            }

            val feedAlreadyExists = existingLinks.any {
                it.href.toHttpUrlOrNull()?.toUri()?.normalize() == outlineUrl.toUri().normalize()
            }

            if (feedAlreadyExists) {
                feedsExisted++
            } else {
                try {
                    val res = api().addFeed(outlineUrl, importCategoryId)
                    withContext(Dispatchers.IO) {
                        db().transaction {
                            db().feed.insertOrReplace(res.feed)
                            db().link.insertForFeed(res.feed.id, res.feedLinks)
                            res.entries.forEach { (entry, links) ->
                                db().entry.insertOrReplace(listOf(entry))
                                db().link.insertForEntry(entry.id, links)
                            }
                        }
                    }
                    feedsImported++
                } catch (e: Throwable) {
                    errors += "Failed to import feed ${outline.xmlUrl}\nReason: ${e.message}"
                    feedsFailed++
                }

                state.update {
                    State.ImportingFeeds(
                        imported = feedsImported + feedsExisted + feedsFailed,
                        total = outlines.size,
                    )
                }
            }
        }

        if (isMiniflux) {
            sync().runInBackground()
        }

        val feeds = db().feed.selectAll()
        state.update { State.ShowingFeeds(feeds.map { it.toItem(db()) }) }

        if (errors.isNotEmpty()) {
            val message = buildString {
                errors.forEach {
                    append(it)

                    if (errors.last() != it) {
                        append("\n\n")
                    }
                }
            }

            showErrorDialog(message)
        }
    }

    private fun exportOpml(out: OutputStream) {
        viewLifecycleOwner.lifecycleScope.launch {
            val feeds = db().feed.selectAll()
            val feedIds = feeds.map { it.id }
            val allLinks = withContext(Dispatchers.IO) {
                feedIds.flatMap { db().link.selectByFeedId(it) }
            }
            val linksByFeedId = allLinks.groupBy { it.feedId }

            val outlines = feeds.map { feed ->
                val feedLinks = linksByFeedId[feed.id] ?: emptyList()
                val selfLink = feedLinks.firstOrNull { it.rel is AtomLinkRel.Self }
                    ?: feedLinks.firstOrNull()
                OpmlOutline(
                    text = feed.title,
                    outlines = emptyList(),
                    xmlUrl = selfLink?.href,
                    htmlUrl = feedLinks.firstOrNull { it.rel is AtomLinkRel.Alternate }?.href,
                    extOpenEntriesInBrowser = feed.extOpenEntriesInBrowser,
                    extShowPreviewImages = feed.extShowPreviewImages,
                    extBlockedWords = feed.extBlockedWords,
                )
            }

            val opmlDocument = OpmlDocument(
                version = OpmlVersion.V_2_0,
                outlines = outlines,
            )

            withContext(Dispatchers.IO) {
                out.write(opmlDocument.toXmlDocument().toPrettyString().toByteArray())
            }
        }
    }

    private fun exportFeedSettings(out: OutputStream) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val feeds = withContext(Dispatchers.IO) { db().feed.selectAll() }
                val backend = withContext(Dispatchers.IO) { db().conf.select().backend }
                val json = org.vestifeed.feedsettings.exportFeedSettings(feeds, backend)
                withContext(Dispatchers.IO) {
                    out.write(json.toByteArray())
                }
            }.onFailure { e -> showErrorDialog(e) }
        }
    }

    private fun addFeed(unvalidatedUrl: String, categoryId: Long?) {
        val trimmedUrl = unvalidatedUrl.trim()
        if (trimmedUrl.startsWith("http://")) {
            showErrorDialog(Exception("HTTP URLs are not supported. Please use HTTPS or enter a domain name."))
            return
        }
        val hasExplicitHttps = trimmedUrl.startsWith("https://")
        val domain = if (hasExplicitHttps) {
            trimmedUrl
        } else {
            "https://$trimmedUrl"
        }
        val parsedUrl = domain.toHttpUrlOrNull()
        if (parsedUrl == null) {
            showErrorDialog(Exception("Invalid URL: $unvalidatedUrl"))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            if (!requestLocalNetworkAccess(listOf(parsedUrl))) {
                showErrorDialog(R.string.local_network_permission_required)
                return@launch
            }

            addFeed(parsedUrl, categoryId)
        }
    }

    private suspend fun addFeed(url: HttpUrl, categoryId: Long?) {
        val prevState = state.value
        state.update { State.Loading }
        try {
            val res = api().addFeed(url, categoryId)
            withContext(Dispatchers.IO) {
                db().transaction {
                    db().feed.insertOrReplace(res.feed)
                    db().link.insertForFeed(res.feed.id, res.feedLinks)
                    res.entries.forEach { (entry, links) ->
                        db().entry.insertOrReplace(listOf(entry))
                        db().link.insertForEntry(entry.id, links)
                    }
                }
            }
            sync().runInBackground()
            val feeds = withContext(Dispatchers.IO) {
                db().feed.selectAll()
            }
            state.update { State.ShowingFeeds(feeds.map { it.toItem(db()) }) }
        } catch (e: Throwable) {
            state.update { prevState }
            showErrorDialog(e)
        }
    }

    private fun renameFeed(feedId: String, newTitle: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val feed = db().feed.selectById(feedId)
                    ?: throw Exception("Cannot find feed $feedId in cache")
                val trimmedNewTitle = newTitle.trim()
                api().updateFeedTitle(feedId, trimmedNewTitle)
                withContext(Dispatchers.IO) {
                    db().feed.insertOrReplace(feed.copy(title = trimmedNewTitle))
                }
                val feeds = withContext(Dispatchers.IO) {
                    db().feed.selectAll()
                }
                state.update { State.ShowingFeeds(feeds.map { it.toItem(db()) }) }
            }.onFailure { e -> showErrorDialog(e) }
        }
    }

    private fun showAddToTagDialog(feedId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val tags = db().tag.selectAll()
                    val current = db().feedTag.selectTagIdsByFeedId(feedId).toSet()
                    Triple(tags, current, BooleanArray(tags.size) { i -> current.contains(tags[i].id) })
                }
            }.onSuccess { (tags, _, checked) ->
                if (tags.isEmpty()) {
                    showErrorDialog(R.string.you_have_no_tags)
                    return@onSuccess
                }
                val tagNames = tags.map { it.name }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.manage_tags)
                    .setMultiChoiceItems(tagNames, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        applyTagChanges(feedId, tags, checked)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }.onFailure { e -> showErrorDialog(e) }
        }
    }

    private fun applyTagChanges(feedId: String, tags: List<TagTable.Tag>, checked: BooleanArray) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val newSelected = tags.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                    val current = db().feedTag.selectTagIdsByFeedId(feedId).toSet()
                    val toAdd = newSelected - current
                    val toRemove = current - newSelected
                    db().transaction {
                        toAdd.forEach { db().feedTag.insert(feedId, it) }
                        toRemove.forEach { db().feedTag.delete(feedId, it) }
                    }
                }
            }.onFailure { e -> showErrorDialog(e) }
        }
    }

    private fun deleteFeed(feedId: String) {
        val prevState = state.value
        state.update { State.Loading }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                api().deleteFeed(feedId)
                withContext(Dispatchers.IO) {
                    db().transaction {
                        db().entry.selectByFeedId(feedId).forEach {
                            db().link.deleteByEntryId(it.id)
                        }
                        db().entry.deleteByFeedId(feedId)
                        db().link.deleteByFeedId(feedId)
                        db().feedTag.deleteByFeedId(feedId)
                        db().feed.deleteById(feedId)
                    }
                }
                val feeds = withContext(Dispatchers.IO) {
                    db().feed.selectAll()
                }
                val items = withContext(Dispatchers.IO) {
                    feeds.map { it.toItem(db()) }
                }
                state.update { State.ShowingFeeds(items) }
            } catch (e: Throwable) {
                state.update { prevState }
                showErrorDialog(e)
            }
        }
    }

    private fun FeedTable.Feed.toItem(database: Database): FeedsAdapter.Item {
        val links = database.link.selectByFeedId(id)
        val selfLink = links.firstOrNull { it.rel is AtomLinkRel.Self }?.href
            ?: links.firstOrNull()?.href
            ?: "https://example.com"

        return FeedsAdapter.Item(
            id = id,
            title = title,
            selfLink = selfLink,
            alternateLink = links.firstOrNull { it.rel is AtomLinkRel.Alternate }?.href,
            unreadCount = database.entry.selectByFeedId(id).filterNot { it.extRead }.size.toLong(),
            confUseBuiltInBrowser = database.conf.select().useBuiltInBrowser,
        )
    }

    private fun FragmentFeedsBinding.setState(state: State) {
        listOf(toolbar, list, progress, message, importOpml, fab).forEach { it.isVisible = false }

        when (state) {
            is State.Loading -> listOf(toolbar, progress).forEach { it.isVisible = true }
            is State.ShowingFeeds -> listOf(toolbar, list, fab).forEach { it.isVisible = true }
            is State.ImportingFeeds -> listOf(toolbar, message).forEach { it.isVisible = true }
        }

        when (state) {
            is State.Loading -> {}

            is State.ShowingFeeds -> {
                (binding.list.adapter as? FeedsAdapter)?.submitList(state.feeds)

                toolbar.title = if (state.feeds.isEmpty()) {
                    getString(R.string.feeds)
                } else {
                    getString(R.string.feeds_n, state.feeds.size)
                }

                if (state.feeds.isEmpty()) {
                    message.isVisible = true
                    message.text = getString(R.string.you_have_no_feeds)
                    importOpml.isVisible = true
                }
            }

            is State.ImportingFeeds -> {
                message.text = getString(
                    R.string.importing_feeds_n_of_n,
                    state.imported,
                    state.total,
                )
            }
        }
    }

    private fun handleAddFeedIntent() {
        val args = arguments ?: return
        val url = args.getString("url", "")

        if (url.isNotBlank()) {
            addFeed(url, null)
            args.clear()
        }
    }

    /**
     * Show the "add feed" dialog. In Miniflux mode the user is forced to
     * pick a category — the server side assigns every feed to a category
     * and the picker is wired to a live `GET /v1/categories` request so the
     * list reflects the current state on the server. The OK button is
     * disabled until a category is selected. In embedded mode the category
     * dropdown is hidden and the dialog falls back to the URL-only flow.
     */
    private fun showAddFeedDialog() {
        val isMiniflux = db().conf.select().backend == ConfTable.Backend.Miniflux

        var selectedCategoryId: Long? = null

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.add_feed))
            .setView(R.layout.dialog_add_feed)
            .setPositiveButton(R.string.add) { dialogInterface, _ ->
                onAddClick(dialogInterface, selectedCategoryId)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()

        val urlView = dialog.findViewById<EditText>(R.id.url)!!
        val categoryLayout = dialog.findViewById<TextInputLayout>(R.id.categoryLayout)!!
        val categoryView = dialog.findViewById<MaterialAutoCompleteTextView>(R.id.category)!!
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

        if (isMiniflux) {
            positiveButton.isEnabled = false
            categoryLayout.isEnabled = false
            categoryLayout.helperText = getString(R.string.loading_categories)

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val miniflux = api() as? Miniflux
                    if (miniflux == null) {
                        showErrorDialog(Exception("Miniflux backend expected but was not active"))
                        dialog.dismiss()
                        return@launch
                    }
                    val categories = withContext(Dispatchers.IO) { miniflux.getCategories() }
                    if (categories.isEmpty()) {
                        showErrorDialog(R.string.no_categories_available)
                        dialog.dismiss()
                        return@launch
                    }
                    val names = categories.map { it.title }.toTypedArray()
                    val adapter = ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_list_item_1,
                        names,
                    )
                    categoryView.setAdapter(adapter)
                    categoryLayout.helperText = null
                    categoryLayout.isEnabled = true
                    categoryView.setOnItemClickListener { _, _, position, _ ->
                        selectedCategoryId = categories[position].id
                        positiveButton.isEnabled = true
                    }
                } catch (e: Throwable) {
                    showErrorDialog(e)
                    dialog.dismiss()
                }
            }
        } else {
            categoryLayout.visibility = View.GONE
        }

        urlView.setOnEditorActionListener { _, actionId, keyEvent ->
            if (actionId == EditorInfo.IME_ACTION_DONE || keyEvent?.keyCode == KeyEvent.KEYCODE_ENTER) {
                if (isMiniflux && selectedCategoryId == null) {
                    return@setOnEditorActionListener true
                }
                dialog.dismiss()
                addFeed(urlView.text.toString(), if (isMiniflux) selectedCategoryId else null)
                return@setOnEditorActionListener true
            }

            false
        }

        urlView.requestFocus()
        urlView.postDelayed({ showKeyboard(urlView) }, 300)
    }

    private fun openTagsManager() {
        parentFragmentManager.commit {
            replace(R.id.fragmentContainerView, TagsFragment::class.java, null)
            addToBackStack(null)
        }
    }

    private fun onAddClick(dialogInterface: DialogInterface, categoryId: Long?) {
        val url =
            (dialogInterface as AlertDialog).findViewById<TextInputEditText>(R.id.url)?.text.toString()
        addFeed(url, categoryId)
    }

    private fun onRenameClick(feedId: String, dialogInterface: DialogInterface) {
        val title = (dialogInterface as AlertDialog).findViewById<TextInputEditText>(R.id.title)!!
        renameFeed(feedId, title.text.toString())
    }

    private fun createFeedsAdapter(): FeedsAdapter {
        return FeedsAdapter(
            callback = object : FeedsAdapter.Callback {
                override fun onClick(item: FeedsAdapter.Item) {
                    parentFragmentManager.commit {
                        replace(
                            R.id.fragmentContainerView,
                            EntriesFragment::class.java,
                            EntriesFilter.BelongToFeed(feedId = item.id).toBundle(),
                        )
                        addToBackStack(null)
                    }
                }

                override fun onSettingsClick(item: FeedsAdapter.Item) {
                    parentFragmentManager.commit {
                        replace(
                            R.id.fragmentContainerView,
                            FeedSettingsFragment::class.java,
                            bundleOf("feedId" to item.id),
                        )
                        addToBackStack(null)
                    }
                }

                override fun onOpenSelfLinkClick(item: FeedsAdapter.Item) {
                    openUrl(
                        url = item.selfLink.toString(),
                        useBuiltInBrowser = item.confUseBuiltInBrowser,
                    )
                }

                override fun onOpenAlternateLinkClick(item: FeedsAdapter.Item) {
                    openUrl(
                        url = item.alternateLink.toString(),
                        useBuiltInBrowser = item.confUseBuiltInBrowser,
                    )
                }

                override fun onAddToTagClick(item: FeedsAdapter.Item) {
                    showAddToTagDialog(item.id)
                }

                override fun onRenameClick(item: FeedsAdapter.Item) {
                    val dialog =
                        MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.rename))
                            .setView(R.layout.dialog_rename_feed)
                            .setPositiveButton(R.string.rename) { dialogInterface, _ ->
                                onRenameClick(
                                    feedId = item.id,
                                    dialogInterface = dialogInterface,
                                )
                            }.setNegativeButton(R.string.cancel, null).show()

                    val title = dialog.findViewById<TextInputEditText>(R.id.title)!!
                    title.append(item.title)

                    title.requestFocus()
                    title.postDelayed({ showKeyboard(title) }, 300)
                }

                override fun onDeleteClick(item: FeedsAdapter.Item) {
                    deleteFeed(item.id)
                }
            },
            tagsEditable = db().conf.select().backend != ConfTable.Backend.Miniflux,
        )
    }

    class ListItemDecoration(context: Context) : RecyclerView.ItemDecoration() {
        private val gapInPixels = context.resources.getDimensionPixelSize(R.dimen.dp_8)

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val adapter = parent.adapter

            if (adapter == null || adapter.itemCount == 0) {
                super.getItemOffsets(outRect, view, parent, state)
                return
            }

            val position = parent.getChildLayoutPosition(view)

            val left = 0
            val top = if (position == 0) gapInPixels else 0
            val right = 0
            val bottom = if (position == adapter.itemCount - 1) gapInPixels else 0

            outRect.set(left, top, right, bottom)
        }
    }
}
