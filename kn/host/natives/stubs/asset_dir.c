/*
 * AAssetManager over a plain directory.
 *
 * ncnnMl.cpp reads its models through the NDK's asset API, and ncnn itself is
 * built with -D__ANDROID_API__=9 so that Net::load_param(AAssetManager*, path)
 * exists (host/natives/build_ncnn.sh).  On Android those come from the apk and
 * the framework's libandroid.so.  Here there is neither, so an "asset manager"
 * is just a directory and an "asset" is a file under it - which is what the
 * models are once the click is unpacked.
 */
/* -std=c11 hides fseeko/ftello and off64_t behind the feature test macros. */
#define _GNU_SOURCE
#define _LARGEFILE64_SOURCE
#define _FILE_OFFSET_BITS 64

#include <android/asset_manager.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct AAssetManager {
	char *root;
};

struct AAsset {
	FILE *file;
	off64_t length;
	void *buffer; /* filled lazily by AAsset_getBuffer */
};

AAssetManager *pc_assets_open_dir(const char *root)
{
	if (!root)
		return NULL;
	AAssetManager *mgr = (AAssetManager *) calloc(1, sizeof(AAssetManager));
	if (!mgr)
		return NULL;
	mgr->root = strdup(root);
	if (!mgr->root) {
		free(mgr);
		return NULL;
	}
	return mgr;
}

void pc_assets_close_dir(AAssetManager *mgr)
{
	if (!mgr)
		return;
	free(mgr->root);
	free(mgr);
}

AAsset *AAssetManager_open(AAssetManager *mgr, const char *filename, int mode)
{
	(void) mode;
	if (!mgr || !filename)
		return NULL;

	size_t n = strlen(mgr->root) + 1 + strlen(filename) + 1;
	char *path = (char *) malloc(n);
	if (!path)
		return NULL;
	snprintf(path, n, "%s/%s", mgr->root, filename);

	FILE *f = fopen(path, "rb");
	free(path);
	if (!f)
		return NULL;

	AAsset *a = (AAsset *) calloc(1, sizeof(AAsset));
	if (!a) {
		fclose(f);
		return NULL;
	}
	a->file = f;
	fseeko(f, 0, SEEK_END);
	a->length = ftello(f);
	fseeko(f, 0, SEEK_SET);
	return a;
}

int AAsset_read(AAsset *asset, void *buf, size_t count)
{
	if (!asset)
		return -1;
	return (int) fread(buf, 1, count, asset->file);
}

off64_t AAsset_seek64(AAsset *asset, off64_t offset, int whence)
{
	if (!asset || fseeko(asset->file, offset, whence) != 0)
		return -1;
	return ftello(asset->file);
}

off_t AAsset_seek(AAsset *asset, off_t offset, int whence)
{
	return (off_t) AAsset_seek64(asset, offset, whence);
}

off64_t AAsset_getLength64(AAsset *asset)
{
	return asset ? asset->length : 0;
}

off_t AAsset_getLength(AAsset *asset)
{
	return (off_t) AAsset_getLength64(asset);
}

off64_t AAsset_getRemainingLength64(AAsset *asset)
{
	if (!asset)
		return 0;
	return asset->length - ftello(asset->file);
}

off_t AAsset_getRemainingLength(AAsset *asset)
{
	return (off_t) AAsset_getRemainingLength64(asset);
}

const void *AAsset_getBuffer(AAsset *asset)
{
	if (!asset)
		return NULL;
	if (asset->buffer)
		return asset->buffer;
	if (asset->length <= 0)
		return NULL;

	void *buf = malloc((size_t) asset->length);
	if (!buf)
		return NULL;
	off64_t saved = ftello(asset->file);
	fseeko(asset->file, 0, SEEK_SET);
	size_t got = fread(buf, 1, (size_t) asset->length, asset->file);
	fseeko(asset->file, saved, SEEK_SET);
	if (got != (size_t) asset->length) {
		free(buf);
		return NULL;
	}
	asset->buffer = buf;
	return buf;
}

void AAsset_close(AAsset *asset)
{
	if (!asset)
		return;
	if (asset->file)
		fclose(asset->file);
	free(asset->buffer);
	free(asset);
}

int AAsset_openFileDescriptor(AAsset *asset, off_t *outStart, off_t *outLength)
{
	(void) asset;
	(void) outStart;
	(void) outLength;
	return -1;
}

/* Directory enumeration: nothing here asks for it. */
AAssetDir *AAssetManager_openDir(AAssetManager *mgr, const char *dirName)
{
	(void) mgr;
	(void) dirName;
	return NULL;
}

const char *AAssetDir_getNextFileName(AAssetDir *dir)
{
	(void) dir;
	return NULL;
}

void AAssetDir_close(AAssetDir *dir)
{
	(void) dir;
}
