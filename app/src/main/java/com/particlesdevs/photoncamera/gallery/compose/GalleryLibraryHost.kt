package com.particlesdevs.photoncamera.gallery.compose

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.particlesdevs.photoncamera.composeui.gallery.GalleryLibraryScreen
import com.particlesdevs.photoncamera.composeui.gallery.LocalThumbnailLoader
import com.particlesdevs.photoncamera.composeui.state.GalleryFolderState
import com.particlesdevs.photoncamera.composeui.state.GalleryItemState
import com.particlesdevs.photoncamera.composeui.state.GalleryLibraryEvent
import com.particlesdevs.photoncamera.composeui.state.GalleryLibraryUiState
import com.particlesdevs.photoncamera.composeui.theme.PhotonTheme
import com.particlesdevs.photoncamera.gallery.model.GalleryItem

/**
 * The image library, drawn by Compose.
 *
 * The fragment keeps owning the data and the actions - the view model, the delete and
 * share flows, navigation - and this only turns the list into rows and the taps back
 * into calls.
 */
class GalleryLibraryHost(
    private val context: Context,
    private val columns: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen(item: GalleryItem, position: Int)
        fun onSelectionChanged(selected: List<@JvmSuppressWildcards GalleryItem>)
        fun onShare()
        fun onDelete()
        fun onCompare()
        fun onSettings()
        fun onBack()
        fun onFolderSelected(folder: GalleryItem)
    }

    var state by mutableStateOf(GalleryLibraryUiState(columns = columns))
        private set

    private var items: List<GalleryItem> = emptyList()
    private var folders: List<GalleryItem> = emptyList()
    private val selected = linkedSetOf<String>()

    fun createView(): View = ComposeView(context).apply {
        setContent {
            PhotonTheme {
                CompositionLocalProvider(LocalThumbnailLoader provides GlideThumbnailLoader(context)) {
                    GalleryLibraryScreen(state, ::onEvent, Modifier.fillMaxSize())
                }
            }
        }
    }

    fun setItems(items: List<GalleryItem>) {
        this.items = items
        selected.retainAll(items.map { it.id }.toSet())
        publish()
    }

    fun setFolders(folders: List<GalleryItem>, selectedFolderId: String?) {
        this.folders = folders
        this.selectedFolderId = selectedFolderId
        publish()
    }

    private var selectedFolderId: String? = null

    fun selectedItems(): List<GalleryItem> = items.filter { it.id in selected }

    fun clearSelection() {
        selected.clear()
        publish()
        listener.onSelectionChanged(emptyList())
    }

    private fun publish() {
        state = GalleryLibraryUiState(
            items = items.map {
                GalleryItemState(it.id, it.displayName.orEmpty(), it.mediaTypeTag.orEmpty(), it.id in selected)
            },
            folders = folders.map {
                GalleryFolderState(it.id, it.displayName.orEmpty(), it.id == selectedFolderId)
            },
            columns = columns,
            selectionMode = selected.isNotEmpty(),
            compareAvailable = selected.size == 2,
        )
    }

    companion object {
        /** A gallery row is keyed by its file's uri. */
        @JvmStatic
        fun idOf(item: GalleryItem): String =
            item.file?.fileUri?.toString() ?: item.displayName.orEmpty()
    }

    private fun onEvent(event: GalleryLibraryEvent) {
        when (event) {
            is GalleryLibraryEvent.Open -> {
                val index = items.indexOfFirst { it.id == event.id }
                if (index >= 0) listener.onOpen(items[index], index)
            }

            is GalleryLibraryEvent.ToggleSelect -> {
                if (!selected.add(event.id)) selected.remove(event.id)
                publish()
                listener.onSelectionChanged(selectedItems())
            }

            is GalleryLibraryEvent.SelectFolder ->
                folders.firstOrNull { it.id == event.id }?.let(listener::onFolderSelected)

            GalleryLibraryEvent.ClearSelection -> clearSelection()
            GalleryLibraryEvent.Share -> listener.onShare()
            GalleryLibraryEvent.Delete -> listener.onDelete()
            GalleryLibraryEvent.Compare -> listener.onCompare()
            GalleryLibraryEvent.Settings -> listener.onSettings()
            GalleryLibraryEvent.Back -> listener.onBack()
        }
    }
}

/** A gallery row is keyed by its file's uri. */
internal val GalleryItem.id: String
    get() = GalleryLibraryHost.idOf(this)
