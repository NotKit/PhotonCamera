package com.particlesdevs.photoncamera.gallery.ui.fragments;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.databinding.Observable;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.viewpager.widget.ViewPager;

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.composeui.state.ExifRow;
import com.particlesdevs.photoncamera.gallery.adapters.DepthPageTransformer;
import com.particlesdevs.photoncamera.gallery.adapters.ImageAdapter;
import com.particlesdevs.photoncamera.gallery.compose.ImageViewerHost;
import com.particlesdevs.photoncamera.gallery.compare.SSIVListener;
import com.particlesdevs.photoncamera.gallery.files.GalleryFileOperations;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.helper.Constants;
import com.particlesdevs.photoncamera.gallery.helper.UltraHdrGalleryUtil;
import com.particlesdevs.photoncamera.gallery.model.ExifDialogModel;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.gallery.viewmodel.ExifDialogViewModel;
import com.particlesdevs.photoncamera.gallery.viewmodel.GalleryViewModel;
import com.particlesdevs.photoncamera.gallery.views.CustomSSIV;
import com.particlesdevs.photoncamera.gallery.views.Histogram;
import org.apache.commons.io.FileUtils;
import com.particlesdevs.photoncamera.processing.ImagePath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The image viewer. {@link ImageViewerHost} draws the bars, the filmstrip and the EXIF
 * panel; the photo itself stays in the ViewPager of subsampling views, which tiles very
 * large files and decodes UltraHDR gain maps.
 */
public class ImageViewerFragment extends Fragment
        implements ImageAdapter.HdrStateListener, ImageViewerHost.Listener {
    private List<GalleryItem> galleryItems = new ArrayList<>(0);
    private ExifDialogViewModel exifDialogViewModel;
    private ViewPager viewPager;
    private ImageAdapter adapter;
    private NavController navController;
    private ImageViewerHost host;
    private String mode;
    private int seek_position = 0;
    private int lastHdrPosition = -1;
    private int indexToDelete = -1;
    private GalleryViewModel viewModel;
    private SSIVListener ssivListener;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, @Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(requireActivity()).get(GalleryViewModel.class);
        exifDialogViewModel = new ViewModelProvider(this).get(ExifDialogViewModel.class);
        exifDialogViewModel.getExifDataModel().addOnPropertyChangedCallback(
                new Observable.OnPropertyChangedCallback() {
                    @Override
                    public void onPropertyChanged(Observable sender, int propertyId) {
                        pushExif();
                    }
                });
        navController = NavHostFragment.findNavController(this);
        host = new ImageViewerHost(requireContext(), this);
        viewPager = host.getViewPager();
        viewModel.getCurrentFolderImages().observe(getViewLifecycleOwner(), this::initImageAdapter);
        return host.createView();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewPager.setPageTransformer(true, new DepthPageTransformer());
        viewPager.setOffscreenPageLimit(3);
        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                host.setIndex(position);
                updateExif();
                updateScaleText();
                onPageHdrSelected(position);
            }
        });
        Bundle bundle = getArguments();
        if (bundle != null) {
            mode = bundle.getString(Constants.MODE_KEY);
            seek_position = bundle.getInt(Constants.IMAGE_POSITION_KEY, 0);
        }
        updateExif();
    }

    private void initImageAdapter(List<GalleryItem> items) {
        if (items == null) return;
        this.galleryItems = items;
        adapter = new ImageAdapter(this.galleryItems);
        adapter.setImageViewClickListener(v -> host.setChromeVisible(!host.getState().getChromeVisible()));
        adapter.setHdrStateListener(this);
        if (ssivListener != null) adapter.setSsivListener(ssivListener);
        adapter.setImageEventListener(new SubsamplingScaleImageView.DefaultOnImageEventListener() {
            @Override
            public void onReady() {
                updateScaleText();
            }
        });
        viewPager.setAdapter(adapter);
        viewPager.setCurrentItem(seek_position);
        host.setItems(this.galleryItems, seek_position);
        host.setCompareAvailable(this.galleryItems.size() >= 2);
        updateExif();
    }

    // ── ImageViewerHost.Listener ───────────────────────────────────────────

    @Override
    public void onBack() {
        requireActivity().finish();
    }

    @Override
    public void onExifVisibilityChanged(boolean visible) {
        updateExif();
    }

    @Override
    public void onSelect(int index) {
        viewPager.setCurrentItem(index);
    }

    @Override
    public void onOpenGrid() {
        if (navController.getPreviousBackStackEntry() == null)
            navController.navigate(R.id.action_imageViewFragment_to_imageLibraryFragment);
        else navController.navigateUp();
    }

    @Override
    public void onCompare() {
        if (galleryItems.size() < 2) {
            Toast.makeText(getContext(), "No images to compare!", Toast.LENGTH_SHORT).show();
            return;
        }
        Bundle b = new Bundle(2);
        int image1pos = viewPager.getCurrentItem();
        int image2pos = image1pos + 1;
        if (image1pos == galleryItems.size() - 1) {
            image2pos = image1pos;
            image1pos -= 1;
        }
        b.putInt(Constants.IMAGE1_KEY, image1pos);
        b.putInt(Constants.IMAGE2_KEY, image2pos);
        navController.navigate(R.id.action_imageViewerFragment_to_imageCompareFragment, b);
    }

    @Override
    public void onEdit() {
        int position = viewPager.getCurrentItem();
        if (galleryItems.isEmpty() || getContext() == null) return;
        GalleryItem galleryItem = galleryItems.get(position);
        String fileName = galleryItem.getFile().getDisplayName();
        String mediaType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(FileUtils.getExtension(fileName));
        Uri uri = galleryItem.getFile().getFileUri();
        Intent editIntent = new Intent(Intent.ACTION_EDIT);
        editIntent.setDataAndType(uri, mediaType);
        String outPutFileUri = uri.toString().replace(fileName,
                ImagePath.generateNewFileName("IMG") + '.' + FileUtils.getExtension(fileName));
        editIntent.putExtra(MediaStore.EXTRA_OUTPUT, outPutFileUri);
        editIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(editIntent, null), Constants.REQUEST_EDIT_IMAGE);
    }

    @Override
    public void onShare() {
        GalleryItem galleryItem = galleryItems.get(viewPager.getCurrentItem());
        String fileName = galleryItem.getFile().getDisplayName();
        String mediaType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(FileUtils.getExtension(fileName));
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_STREAM, galleryItem.getFile().getFileUri());
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType(mediaType);
        startActivity(Intent.createChooser(intent, null));
    }

    @Override
    public void onDelete() {
        new AlertDialog.Builder(requireContext())
                .setMessage(R.string.sure_delete)
                .setTitle(android.R.string.dialog_alert_title)
                .setIcon(R.drawable.ic_delete)
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss())
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    indexToDelete = viewPager.getCurrentItem();
                    GalleryFileOperations.deleteImageFiles(getActivity(),
                            Collections.singletonList((ImageFile) galleryItems.get(indexToDelete).getFile()),
                            this::handleImagesDeletedCallback);
                })
                .create()
                .show();
    }

    @Override
    public void onToggleHdr() {
        if (adapter == null) return;
        int position = viewPager.getCurrentItem();
        if (!adapter.isHdrAvailable(position)) return;
        if (adapter.isHdrActive(position)) {
            adapter.releaseHdrForPosition(getSsivAt(position), position);
            UltraHdrGalleryUtil.setWindowHdr(getActivity(), false);
            host.setHdr(true, false);
        } else {
            adapter.loadHdrForPosition(getSsivAt(position), position);
        }
    }

    // ── UltraHDR ──────────────────────────────────────────────────────────

    @Override
    public void onResume() {
        super.onResume();
        if (adapter == null) return;
        int position = viewPager.getCurrentItem();
        if (adapter.isHdrActive(position)) {
            UltraHdrGalleryUtil.setWindowHdr(getActivity(), true);
            host.setHdr(adapter.isHdrAvailable(position), true);
        } else {
            adapter.loadHdrForPosition(getSsivAt(position), position);
            host.setHdr(adapter.isHdrAvailable(position), false);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        UltraHdrGalleryUtil.setWindowHdr(getActivity(), false);
        if (adapter != null && viewPager != null) {
            host.setHdr(adapter.isHdrAvailable(viewPager.getCurrentItem()), false);
        }
    }

    private void onPageHdrSelected(int position) {
        if (adapter == null) return;
        if (lastHdrPosition >= 0 && lastHdrPosition != position) {
            adapter.releaseHdrForPosition(getSsivAt(lastHdrPosition), lastHdrPosition);
        }
        lastHdrPosition = position;
        adapter.loadHdrForPosition(getSsivAt(position), position);
        if (!isCompareMode() && getActivity() != null) {
            UltraHdrGalleryUtil.setWindowHdr(getActivity(), adapter.isHdrActive(position));
        }
    }

    @Override
    public void onHdrStateChanged(int position, boolean isHdr) {
        if (getActivity() != null && viewPager != null && position == viewPager.getCurrentItem()) {
            UltraHdrGalleryUtil.setWindowHdr(getActivity(), isHdr);
            host.setHdr(adapter != null && adapter.isHdrAvailable(position), isHdr);
        }
    }

    @Override
    public void onHdrAvailabilityChanged(int position, boolean isUltraHdr) {
        if (viewPager != null && position == viewPager.getCurrentItem()) {
            host.setHdr(isUltraHdr, adapter != null && adapter.isHdrActive(position));
        }
    }

    // ── compare mode hooks, used by ImageCompareFragment ───────────────────

    public void setSsivListener(SSIVListener ssivListener) {
        this.ssivListener = ssivListener;
    }

    public CustomSSIV getCurrentSSIV() {
        return getSsivAt(viewPager.getCurrentItem());
    }

    private CustomSSIV getSsivAt(int position) {
        if (adapter == null || viewPager == null) return null;
        return viewPager.findViewById(adapter.getSsivId(position));
    }

    public void updateScaleText() {
        SubsamplingScaleImageView view = getCurrentSSIV();
        if (view != null) {
            host.setScaleLabel(String.format(Locale.ROOT, "%.0f%%", view.getScale() * 100));
        }
    }

    public void resetScaleText() {
        host.setScaleLabel("");
    }

    private boolean isCompareMode() {
        return mode != null && mode.equalsIgnoreCase(Constants.COMPARE);
    }

    /** Copies the EXIF model and the histogram into the screen's state. */
    private void updateExif() {
        if (galleryItems.isEmpty()) return;
        GalleryItem galleryItem = galleryItems.get(viewPager.getCurrentItem());
        exifDialogViewModel.updateModel(requireContext().getContentResolver(), galleryItem.getFile());
        if (host.getState().getExifVisible()) {
            exifDialogViewModel.updateHistogramView((ImageFile) galleryItem.getFile());
        }
        pushExif();
    }

    private void pushExif() {
        ExifDialogModel model = exifDialogViewModel.getExifDataModel();
        List<ExifRow> rows = new ArrayList<>(9);
        addRow(rows, "", model.getTitle());
        addRow(rows, getString(R.string.resolution), model.getRes());
        addRow(rows, "MP", model.getRes_mp());
        addRow(rows, getString(R.string.device), model.getDevice());
        addRow(rows, getString(R.string.date), model.getDate());
        addRow(rows, getString(R.string.exposure_time), model.getExposure());
        addRow(rows, getString(R.string.iso), model.getIso());
        addRow(rows, getString(R.string.aperture), model.getFnum());
        addRow(rows, "mm", model.getFocal());
        addRow(rows, "Size", model.getFile_size());
        host.setExif(rows, histogramBins(model.getHistogramModel()));
    }

    private static void addRow(List<ExifRow> rows, String label, String value) {
        if (value != null && !value.isEmpty()) rows.add(new ExifRow(label, value));
    }

    /** Histogram.HistogramModel holds one row per channel; the panel draws the luma. */
    private static List<Float> histogramBins(Histogram.HistogramModel model) {
        List<Float> bins = new ArrayList<>(0);
        if (model == null || model.getColorsMap() == null || model.getColorsMap().length == 0) return bins;
        int[] luma = model.getColorsMap()[model.getColorsMap().length - 1];
        for (int value : luma) bins.add((float) value);
        return bins;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != Constants.REQUEST_EDIT_IMAGE) return;
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            Toast.makeText(getContext(), "Saved : " + data.getData().getPath(), Toast.LENGTH_LONG).show();
            viewModel.fetchAllMedia();
            initImageAdapter(viewModel.getCurrentFolderImages().getValue());
            updateExif();
        }
    }

    public void handleImagesDeletedCallback(boolean isDeleted) {
        if (!isDeleted || indexToDelete < 0) {
            Toast.makeText(getContext(), "Deletion Failed!", Toast.LENGTH_SHORT).show();
            return;
        }
        galleryItems.remove(indexToDelete);
        seek_position = Math.max(0, indexToDelete - 1);
        if (!galleryItems.isEmpty()) initImageAdapter(galleryItems);
        updateExif();
        Toast.makeText(getContext(), R.string.image_deleted, Toast.LENGTH_SHORT).show();
        indexToDelete = -1;
        if (galleryItems.isEmpty()) {
            viewModel.setUpdatePending(true);
            navController.navigateUp();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getParentFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
    }
}
