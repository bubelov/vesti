package org.vestifeed.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import org.vestifeed.navigation.AppFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.vestifeed.R
import org.vestifeed.backend.Miniflux
import org.vestifeed.backend.MinifluxUnauthenticatedException
import org.vestifeed.backend.minifluxHttpClient
import org.vestifeed.app.db
import org.vestifeed.app.sync
import org.vestifeed.databinding.FragmentMinifluxAuthBinding
import org.vestifeed.db.table.ConfTable
import org.vestifeed.dialog.showErrorDialog
import org.vestifeed.entries.EntriesFilter
import org.vestifeed.entries.EntriesFragment
import org.vestifeed.entries.toBundle
import org.vestifeed.sync.BackgroundSyncScheduler

class MinifluxAuthFragment : AppFragment() {

    private var _binding: FragmentMinifluxAuthBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMinifluxAuthBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.apply {
            toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

            token.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    connect()
                    return@setOnEditorActionListener true
                }

                false
            }

            connect.setOnClickListener { connect() }
        }
    }

    private fun connect() {
        if (binding.progress.isVisible) {
            return
        }
        if (!binding.validate()) {
            return
        }

        binding.progress.isVisible = true

        val url = binding.url.text.toString().toHttpUrl()
        val token = binding.token.text.toString()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!requestLocalNetworkAccess(listOf(url))) {
                    showErrorDialog(R.string.local_network_permission_required)
                    return@launch
                }

                val api = Miniflux(
                    client = minifluxHttpClient(token = token),
                    baseUrl = "${url.toString().trimEnd('/')}${Miniflux.API_PATH}".toHttpUrl(),
                    db = db(),
                )

                api.getFeeds()

                db().conf.update {
                    it.copy(
                        backend = ConfTable.Backend.Miniflux,
                        minifluxUrl = url.toString().trimEnd('/'),
                        minifluxToken = token,
                    )
                }

                AuthEvents.reset()

                val syncScheduler = BackgroundSyncScheduler(requireContext())
                syncScheduler.schedule()

                sync().runInBackground()

                withResumed {
                    parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

                    parentFragmentManager.commit {
                        replace(
                            R.id.fragmentContainerView,
                            EntriesFragment::class.java,
                            EntriesFilter.Unread.toBundle(),
                        )
                    }
                }
            } catch (_: MinifluxUnauthenticatedException) {
                // The errorInterceptor already reported the invalidation to
                // AuthEvents; the activity will log out and pop back to the
                // backend selection screen, so no dialog is needed here.
            } catch (e: Throwable) {
                showErrorDialog(
                    when (e) {
                        is java.net.UnknownHostException,
                            -> getString(R.string.host_not_reachable)

                        is java.net.ConnectException,
                        is java.net.SocketTimeoutException,
                        is java.net.NoRouteToHostException,
                            -> getString(R.string.server_not_responding)

                        else -> e.message ?: getString(R.string.direct_login_failed)
                    }
                )
            } finally {
                _binding?.progress?.isVisible = false
            }
        }
    }

    private fun FragmentMinifluxAuthBinding.validate(): Boolean {
        urlLayout.error = when (url.text.toString().length) {
            0 -> getString(R.string.field_is_empty)
            else -> null
        }

        if (urlLayout.error == null) {
            val parsed = url.text.toString().toHttpUrlOrNull()
            urlLayout.error = if (parsed == null) {
                getString(R.string.invalid_url)
            } else {
                null
            }
        }

        tokenLayout.error = if (token.text.isNullOrEmpty()) {
            getString(R.string.field_is_empty)
        } else {
            null
        }

        return urlLayout.error == null && tokenLayout.error == null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
