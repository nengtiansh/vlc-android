/*
 * *************************************************************************
 *  MediaBrowserFragment.java
 * **************************************************************************
 *  Copyright © 2015-2016 VLC authors and VideoLAN
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc.gui.browser

import android.content.Intent
import android.os.Bundle
import android.util.SparseBooleanArray
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.*
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.interfaces.media.Playlist
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.AndroidDevices
import org.videolan.resources.TAG_ITEM
import org.videolan.tools.MultiSelectHelper
import org.videolan.tools.isStarted
import org.videolan.vlc.R
import org.videolan.vlc.gui.AudioPlayerContainerActivity
import org.videolan.vlc.gui.ContentActivity
import org.videolan.vlc.gui.InfoActivity
import org.videolan.vlc.gui.MainActivity
import org.videolan.vlc.gui.helpers.SparseBooleanArrayParcelable
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.helpers.UiTools.snackerConfirm
import org.videolan.vlc.gui.helpers.hf.StoragePermissionsDelegate.Companion.getWritePermission
import org.videolan.vlc.gui.view.SwipeRefreshLayout
import org.videolan.vlc.interfaces.Filterable
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.util.Permissions
import org.videolan.vlc.viewmodels.MedialibraryViewModel
import org.videolan.vlc.viewmodels.SortableModel
import java.lang.Runnable

private const val TAG = "VLC/MediaBrowserFragment"
private const val KEY_SELECTION = "key_selection"

@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
abstract class MediaBrowserFragment<T : SortableModel> : Fragment(), ActionMode.Callback, Filterable {

    private lateinit var searchButtonView: View
    lateinit var swipeRefreshLayout: SwipeRefreshLayout
    lateinit var mediaLibrary: Medialibrary
    var actionMode: ActionMode? = null
    var fabPlay: FloatingActionButton? = null
    private var savedSelection = SparseBooleanArray()
    private val transition = ChangeBounds().apply {
        interpolator = AccelerateDecelerateInterpolator()
        duration = 300
    }

    open lateinit var viewModel: T
        protected set
    open val hasTabs = false

    private var refreshJob : Job? = null
        set(value) {
            field?.cancel()
            field = value
        }

    abstract fun getTitle(): String
    abstract fun getMultiHelper(): MultiSelectHelper<T>?

    open val subTitle: String?
        get() = null

    val menu: Menu?
        get() = (activity as? AudioPlayerContainerActivity)?.menu

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaLibrary = Medialibrary.getInstance()
        setHasOptionsMenu(!AndroidDevices.isAndroidTv)
        (savedInstanceState?.getParcelable<SparseBooleanArrayParcelable>(KEY_SELECTION))?.let { savedSelection = it.data }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        searchButtonView = view.findViewById(R.id.searchButton)
        view.findViewById<SwipeRefreshLayout>(R.id.swipeLayout)?.let {
            swipeRefreshLayout = it
            it.setColorSchemeResources(R.color.orange700)
        }
        if (hasFAB()) fabPlay = requireActivity().findViewById(R.id.fab)
    }

    protected open fun hasFAB() = ::swipeRefreshLayout.isInitialized

    protected open fun setBreadcrumb() {
        activity?.findViewById<RecyclerView>(R.id.ariane)?.visibility = View.GONE
    }

    private fun releaseBreadCrumb() {
        activity?.findViewById<RecyclerView>(R.id.ariane)?.adapter = null
    }

    override fun onStart() {
        super.onStart()
        setBreadcrumb()
        updateActionBar()
        setFabPlayVisibility(true)
        fabPlay?.setOnClickListener { v -> onFabPlayClick(v) }
    }

    override fun onStop() {
        super.onStop()
        releaseBreadCrumb()
        setFabPlayVisibility(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        getMultiHelper()?.let {
            outState.putParcelable(KEY_SELECTION, SparseBooleanArrayParcelable(it.selectionMap))
        }
        super.onSaveInstanceState(outState)
    }

    private fun updateActionBar() {
        val activity = activity as? AppCompatActivity ?: return
        activity.supportActionBar?.let {
            it.title = getTitle()
            it.subtitle = subTitle
            activity.invalidateOptionsMenu()
        }
        if (activity is ContentActivity) activity.setTabLayoutVisibility(hasTabs)
    }


    open fun setFabPlayVisibility(enable: Boolean) {
        fabPlay?.run {
            if (enable) show()
            else hide()
        }
    }

    open fun onFabPlayClick(view: View) {}
    abstract fun onRefresh()
    open fun clear() {}

    protected open fun removeItems(items: List<MediaLibraryItem>) {
        if (items.size == 1) {
            removeItem(items[0])
            return
        }
        val v = view ?: return
        lifecycleScope.snackerConfirm(v,getString(R.string.confirm_delete_several_media, items.size)) {
            for (item in items) {
                if (!isStarted()) break
                when(item) {
                    is MediaWrapper -> if (getWritePermission(item.uri)) MediaUtils.deleteMedia(item)
                    is Playlist -> withContext(Dispatchers.IO) { item.delete() }
                }
            }
            if (isStarted()) viewModel.refresh()
        }
    }

    protected open fun removeItem(item: MediaLibraryItem): Boolean {
        val view = view ?: return false
        when (item) {
            is Playlist -> lifecycleScope.snackerConfirm(view, getString(R.string.confirm_delete_playlist, item.title)) { MediaUtils.deletePlaylist(item) }
            is MediaWrapper -> {
                val deleteAction = Runnable {
                    if (isStarted()) lifecycleScope.launch {
                        if (!MediaUtils.deleteMedia(item, null)) onDeleteFailed(item)
                    }
                }
                val resid = if (item.type == MediaWrapper.TYPE_DIR) R.string.confirm_delete_folder else R.string.confirm_delete
                lifecycleScope.snackerConfirm(view, getString(resid, item.getTitle())) { if (Permissions.checkWritePermission(requireActivity(), item, deleteAction)) deleteAction.run() }
            }
            else -> return false
        }
        return true
    }

    private fun onDeleteFailed(item: MediaLibraryItem) {
        if (isAdded) view?.let { UiTools.snacker(it, getString(R.string.msg_delete_failed, item.title)) }
    }

    protected fun showInfoDialog(item: MediaLibraryItem) {
        val i = Intent(activity, InfoActivity::class.java)
        i.putExtra(TAG_ITEM, item)
        startActivity(i)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        (viewModel as? MedialibraryViewModel)?.run {
            menu.findItem(R.id.ml_menu_sortby).isVisible = canSortByName()
            menu.findItem(R.id.ml_menu_sortby_filename).isVisible = canSortByFileNameName()
            menu.findItem(R.id.ml_menu_sortby_artist_name).isVisible = canSortByArtist()
            menu.findItem(R.id.ml_menu_sortby_album_name).isVisible = canSortByAlbum()
            menu.findItem(R.id.ml_menu_sortby_length).isVisible = canSortByDuration()
            menu.findItem(R.id.ml_menu_sortby_date).isVisible = canSortByReleaseDate()
            menu.findItem(R.id.ml_menu_sortby_last_modified).isVisible = canSortByLastModified()
            menu.findItem(R.id.ml_menu_sortby_number).isVisible = false
        }
        sortMenuTitles()
    }

    open fun sortMenuTitles() {
        (viewModel as? MedialibraryViewModel)?.let { model ->
            menu?.let { UiTools.updateSortTitles(it, model.providers[0]) }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.ml_menu_sortby_name -> {
                sortBy(Medialibrary.SORT_ALPHA)
                return true
            }
            R.id.ml_menu_sortby_filename -> {
                sortBy(Medialibrary.SORT_FILENAME)
                return true
            }
            R.id.ml_menu_sortby_length -> {
                sortBy(Medialibrary.SORT_DURATION)
                return true
            }
            R.id.ml_menu_sortby_date -> {
                sortBy(Medialibrary.SORT_RELEASEDATE)
                return true
            }
            R.id.ml_menu_sortby_last_modified -> {
                sortBy(Medialibrary.SORT_LASTMODIFICATIONDATE)
                return true
            }
            R.id.ml_menu_sortby_artist_name -> {
                sortBy(Medialibrary.SORT_ARTIST)
                return true
            }
            R.id.ml_menu_sortby_album_name -> {
                sortBy(Medialibrary.SORT_ALBUM)
                return true
            }
            R.id.ml_menu_sortby_number -> {
                sortBy(Medialibrary.SORT_FILESIZE) //TODO
                return super.onOptionsItemSelected(item)
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    protected open fun sortBy(sort: Int) {
        viewModel.sort(sort)
    }

    fun startActionMode() {
        val activity = activity as AppCompatActivity? ?: return
        actionMode = activity.startSupportActionMode(this)
        setFabPlayVisibility(false)
    }

    protected fun stopActionMode() {
        actionMode?.let {
            it.finish()
            setFabPlayVisibility(true)
        }
    }

    fun invalidateActionMode() {
        actionMode?.invalidate()
    }

    fun restoreMultiSelectHelper() {
        getMultiHelper()?.let {

            if (savedSelection.size() > 0) {
                var hasOneSelected = false
                for (i in 0 until savedSelection.size()) {

                    it.selectionMap.append(savedSelection.keyAt(i), savedSelection.valueAt(i))
                    if (savedSelection.valueAt(i)) hasOneSelected = true
                }
                if (hasOneSelected) startActionMode()
                savedSelection.clear()
            }
        }
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

    override fun filter(query: String) = viewModel.filter(query)

    override fun restoreList() = viewModel.restore()

    override fun enableSearchOption() = true

    override fun getFilterQuery() = viewModel.filterQuery

    override fun setSearchVisibility(visible: Boolean) {
        if (searchButtonView.visibility == View.VISIBLE == visible) return
        if (searchButtonView.parent is ConstraintLayout) {
            val cl = searchButtonView.parent as ConstraintLayout
            val cs = ConstraintSet()
            cs.clone(cl)
            cs.setVisibility(R.id.searchButton, if (visible) ConstraintSet.VISIBLE else ConstraintSet.GONE)
            transition.excludeChildren(RecyclerView::class.java, true)
            TransitionManager.beginDelayedTransition(cl, transition)
            cs.applyTo(cl)
        } else
            searchButtonView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun allowedToExpand() = true

    protected fun setRefreshing(refreshing: Boolean, action: ((loading: Boolean) -> Unit)? = null) {
        refreshJob = lifecycleScope.launchWhenStarted {
            if (refreshing) delay(300L)
            swipeRefreshLayout.isRefreshing = refreshing
            (activity as? MainActivity)?.refreshing = refreshing
            action?.invoke(refreshing)
        }
    }
}
