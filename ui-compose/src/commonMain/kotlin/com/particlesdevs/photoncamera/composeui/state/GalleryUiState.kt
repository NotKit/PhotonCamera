package com.particlesdevs.photoncamera.composeui.state

import androidx.compose.runtime.Immutable

@Immutable
data class GalleryItemState(
    val id: String,
    val displayName: String,
    /** "RAW", "JPG", ... - what GalleryItem.getMediaTypeTag returned. */
    val typeTag: String,
    val selected: Boolean = false,
)

@Immutable
data class GalleryFolderState(val id: String, val label: String, val selected: Boolean = false)

@Immutable
data class GalleryLibraryUiState(
    val items: List<GalleryItemState> = emptyList(),
    val folders: List<GalleryFolderState> = emptyList(),
    val columns: Int = 3,
    val selectionMode: Boolean = false,
    val compareAvailable: Boolean = false,
) {
    val selectedCount: Int get() = items.count { it.selected }
}

sealed interface GalleryLibraryEvent {
    object Back : GalleryLibraryEvent
    object Share : GalleryLibraryEvent
    object Delete : GalleryLibraryEvent
    object Compare : GalleryLibraryEvent
    object Settings : GalleryLibraryEvent
    object ClearSelection : GalleryLibraryEvent
    data class Open(val id: String) : GalleryLibraryEvent
    data class ToggleSelect(val id: String) : GalleryLibraryEvent
    data class SelectFolder(val id: String) : GalleryLibraryEvent
}

@Immutable
data class ExifRow(val label: String, val value: String)

@Immutable
data class ImageViewerUiState(
    val items: List<GalleryItemState> = emptyList(),
    val index: Int = 0,
    val exifVisible: Boolean = false,
    val exifRows: List<ExifRow> = emptyList(),
    val histogram: List<Float> = emptyList(),
    val chromeVisible: Boolean = true,
    val compareAvailable: Boolean = false,
    /** UltraHDR: the file carries a gain map, and whether it is being applied. */
    val hdrAvailable: Boolean = false,
    val hdrActive: Boolean = false,
    val scaleLabel: String = "",
) {
    val current: GalleryItemState? get() = items.getOrNull(index)
}

sealed interface ImageViewerEvent {
    object Back : ImageViewerEvent
    object Delete : ImageViewerEvent
    object Share : ImageViewerEvent
    object Edit : ImageViewerEvent
    object Compare : ImageViewerEvent
    object ToggleExif : ImageViewerEvent
    object ToggleChrome : ImageViewerEvent
    object OpenGrid : ImageViewerEvent
    object ToggleHdr : ImageViewerEvent
    data class Select(val index: Int) : ImageViewerEvent
}
