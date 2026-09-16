package com.particlesdevs.photoncamera.gallery.ui.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.snackbar.Snackbar;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.compose.GalleryLibraryHost;
import com.particlesdevs.photoncamera.gallery.files.GalleryFileOperations;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.helper.Constants;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.gallery.viewmodel.GalleryViewModel;

import org.apache.commons.io.FileUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The image library. The grid, the folder strip and the action buttons are drawn by
 * {@link GalleryLibraryHost}; what stays here is the data, the dialogs and navigation.
 */
public class ImageLibraryFragment extends Fragment implements GalleryLibraryHost.Listener {
    private NavController navController;
    private GalleryLibraryHost host;
    private List<GalleryItem> galleryItems = new ArrayList<>();
    private GalleryViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        navController = NavHostFragment.findNavController(this);
        navController.addOnDestinationChangedListener((c, d, b) -> onImageSelectionStopped());
        OnBackPressedCallback back = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (host != null && !host.selectedItems().isEmpty()) onImageSelectionStopped();
                else navController.navigateUp();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), back);
        host = new GalleryLibraryHost(requireContext(),
                getResources().getInteger(R.integer.grid_columns), this);
        return host.createView();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(GalleryViewModel.class);
        viewModel.getCurrentFolderImages().observe(getViewLifecycleOwner(), this::onImagesChanged);
        viewModel.getSelectedDisplayFolders().observe(getViewLifecycleOwner(), this::onFoldersChanged);
        viewModel.getUpdatePending().observe(getViewLifecycleOwner(), this::onUpdatePending);
    }

    private void onImagesChanged(List<GalleryItem> items) {
        if (items == null) return;
        galleryItems = items;
        host.setItems(items);
    }

    private void onFoldersChanged(List<GalleryItem> folders) {
        if (folders == null) return;
        GalleryItem current = viewModel.getAllSelectedImageFolder().getValue();
        host.setFolders(folders, current == null ? null : GalleryLibraryHost.idOf(current));
    }

    private void onUpdatePending(Boolean pending) {
        if (Boolean.TRUE.equals(pending)) {
            viewModel.fetchAllMedia();
            viewModel.setCurrentFolderImages(viewModel.getAllSelectedImageFolder().getValue());
            viewModel.setUpdatePending(false);
        }
    }

    // ── GalleryLibraryHost.Listener ────────────────────────────────────────

    @Override
    public void onOpen(@NonNull GalleryItem item, int position) {
        Bundle b = new Bundle();
        b.putInt(Constants.IMAGE_POSITION_KEY, position);
        navController.navigate(R.id.action_imageLibraryFragment_to_imageViewerFragment, b);
    }

    @Override
    public void onSelectionChanged(@NonNull List<GalleryItem> selected) {
    }

    @Override
    public void onShare() {
        ArrayList<Uri> imageUris = host.selectedItems().stream()
                .map(item -> item.getFile().getFileUri())
                .collect(Collectors.toCollection(ArrayList::new));
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, imageUris);
        shareIntent.setType("image/*");
        startActivity(Intent.createChooser(shareIntent, null));
    }

    @Override
    public void onDelete() {
        List<GalleryItem> filesToDelete = host.selectedItems();
        String numOfFiles = String.valueOf(filesToDelete.size());
        String totalFileSize = FileUtils.byteCountToDisplaySize(
                (int) filesToDelete.stream().mapToLong(item -> item.getFile().getSize()).sum());
        new AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.sure_delete_multiple, numOfFiles, totalFileSize))
                .setTitle(android.R.string.dialog_alert_title)
                .setIcon(R.drawable.ic_delete)
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss())
                .setPositiveButton(R.string.yes, (dialog, which) -> GalleryFileOperations.deleteImageFiles(
                        getActivity(),
                        filesToDelete.stream().map(item -> (ImageFile) item.getFile()).collect(Collectors.toList()),
                        this::handleImagesDeletedCallback))
                .create()
                .show();
    }

    @Override
    public void onCompare() {
        List<GalleryItem> selectedItems = host.selectedItems();
        if (selectedItems.size() != 2) return;
        Bundle b = new Bundle(2);
        b.putInt(Constants.IMAGE1_KEY, galleryItems.indexOf(selectedItems.get(0)));
        b.putInt(Constants.IMAGE2_KEY, galleryItems.indexOf(selectedItems.get(1)));
        navController.navigate(R.id.action_imageLibraryFragment_to_imageCompareFragment, b);
    }

    @Override
    public void onSettings() {
        navController.navigate(R.id.action_imageLibraryFragment_to_gallerySettingsFragment);
        host.clearSelection();
    }

    @Override
    public void onBack() {
        navController.navigateUp();
    }

    @Override
    public void onFolderSelected(@NonNull GalleryItem folder) {
        onImageSelectionStopped();
        viewModel.setCurrentFolderImages(folder);
    }

    public void onImageSelectionStopped() {
        if (host != null) host.clearSelection();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getParentFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
    }

    public void handleImagesDeletedCallback(boolean isDeleted) {
        View view = getView();
        if (!isDeleted) {
            if (view != null) Snackbar.make(view, "Deletion Failed!", Snackbar.LENGTH_SHORT).show();
            return;
        }
        List<GalleryItem> deleted = host.selectedItems();
        String numOfFiles = String.valueOf(deleted.size());
        String totalFileSize = FileUtils.byteCountToDisplaySize(
                (int) deleted.stream().mapToLong(item -> item.getFile().getSize()).sum());
        galleryItems.removeAll(deleted);
        host.setItems(galleryItems);
        onImageSelectionStopped();
        if (galleryItems.isEmpty()) viewModel.setUpdatePending(true);
        if (view != null) {
            Snackbar.make(view, getString(R.string.multiple_deleted_success, numOfFiles, totalFileSize),
                    Snackbar.LENGTH_SHORT).show();
        }
    }
}
