#ifndef ATL_PREVIEW_TEXTURE_H
#define ATL_PREVIEW_TEXTURE_H

struct atl_preview_texture;

struct atl_preview_texture *atl_preview_texture_new(void);
unsigned atl_preview_texture_update(struct atl_preview_texture *preview, void *hardware,
                                    int width, int height, int *out_width, int *out_height);
void atl_preview_texture_free(struct atl_preview_texture *preview);

#endif
