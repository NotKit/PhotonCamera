package com.particlesdevs.photoncamera.gallery.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.particlesdevs.photoncamera.gallery.files.GalleryFileOperations;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;

import java.util.ArrayList;
import java.util.List;

public class GalleryViewModel extends AndroidViewModel {
    private final MutableLiveData<GalleryItem> allSelectedImagesFolder = new MutableLiveData<>(GalleryItem.createEmpty());
    private final MutableLiveData<List<GalleryItem>> selectedDisplayFolders = new MutableLiveData<>(new ArrayList<>(0));

    private final MutableLiveData<List<GalleryItem>> currentFolderImages = new MutableLiveData<>(new ArrayList<>(0));
    private final MutableLiveData<Boolean> updatePendingLiveData=new MutableLiveData<>(false);


    public MutableLiveData<Boolean> getUpdatePending() {
        return updatePendingLiveData;
    }

    public void setUpdatePending(boolean updatePending) {
        updatePendingLiveData.setValue(updatePending);
    }

    public GalleryViewModel(@NonNull Application application) {
        super(application);
    }

    public void fetchAllMedia() {
        List<GalleryItem> allFolders = new ArrayList<>();
        for (GalleryFileOperations.ImagesFolder imagesFolder :
                GalleryFileOperations._fetchSelectedFolders(getApplication().getContentResolver())) {
            GalleryItem folder = new GalleryItem(imagesFolder.getTopImage());
            for (ImageFile imageFile : imagesFolder.getAllImageFiles()) {
                folder.getFiles().add(new GalleryItem(imageFile));
            }
            folder.setDisplayName(imagesFolder.getFolderName());
            allFolders.add(folder);
        }
        
        ArrayList<ImageFile> all = (ArrayList<ImageFile>) GalleryFileOperations.extractAllSelectedImages();
        if (!all.isEmpty()) {
            GalleryItem allFolder = new GalleryItem(all.get(0));
            allFolder.setDisplayName("ALL");
            for (ImageFile imageFile : all) {
                allFolder.getFiles().add(new GalleryItem(imageFile));
            }
            allSelectedImagesFolder.setValue(allFolder);

            List<GalleryItem> ordered = new ArrayList<>();
            ordered.add(allFolder);
            ordered.addAll(allFolders);
            allFolders = ordered;
        }
        selectedDisplayFolders.setValue(allFolders);
    }

    public LiveData<GalleryItem> getAllSelectedImageFolder() {
        return allSelectedImagesFolder;
    }

    public MutableLiveData<List<GalleryItem>> getCurrentFolderImages() {
        return currentFolderImages;
    }

    public void setCurrentFolderImages(GalleryItem currentFolder) {
        currentFolderImages.setValue(currentFolder.getFiles());
    }

    public MutableLiveData<List<GalleryItem>> getSelectedDisplayFolders() {
        return selectedDisplayFolders;
    }
}
