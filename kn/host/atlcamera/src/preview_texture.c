#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include "preview_texture.h"

#ifndef EGL_NATIVE_BUFFER_ANDROID
#define EGL_NATIVE_BUFFER_ANDROID 0x3140
#endif
#ifndef GL_TEXTURE_EXTERNAL_OES
#define GL_TEXTURE_EXTERNAL_OES 0x8D65
#endif

#define PREVIEW_MAX 1280

struct atl_preview_texture {
	EGLClientBuffer (*native_client)(const void *);
	PFNEGLCREATEIMAGEKHRPROC create_image;
	PFNEGLDESTROYIMAGEKHRPROC destroy_image;
	PFNGLEGLIMAGETARGETTEXTURE2DOESPROC image_texture;
	EGLDisplay display;
	EGLContext context;
	EGLImageKHR image;
	GLuint external;
	GLuint program;
	GLint position;
	GLint texcoord;
	GLint sampler;
	bool failed;
};

static GLuint compile_shader(GLenum type, const char *source)
{
	GLuint shader = glCreateShader(type);
	GLint ok = 0;

	glShaderSource(shader, 1, &source, NULL);
	glCompileShader(shader);
	glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
	if (!ok) {
		char log[512] = {0};
		glGetShaderInfoLog(shader, sizeof(log) - 1, NULL, log);
		fprintf(stderr, "preview texture: shader compile failed: %s\n", log);
		glDeleteShader(shader);
		return 0;
	}
	return shader;
}

static bool prepare(struct atl_preview_texture *preview)
{
	static const char vertex_source[] =
		"attribute vec2 position;\n"
		"attribute vec2 texcoord;\n"
		"varying vec2 uv;\n"
		"void main() { gl_Position = vec4(position, 0.0, 1.0); uv = texcoord; }\n";
	static const char fragment_source[] =
		"#extension GL_OES_EGL_image_external : require\n"
		"precision mediump float;\n"
		"varying vec2 uv;\n"
		"uniform samplerExternalOES frame;\n"
		"void main() { gl_FragColor = texture2D(frame, uv); }\n";
	GLuint vertex, fragment;
	GLint linked = 0;

	if (preview->program)
		return true;
	preview->native_client = (void *)eglGetProcAddress("eglGetNativeClientBufferANDROID");
	preview->create_image = (void *)eglGetProcAddress("eglCreateImageKHR");
	preview->destroy_image = (void *)eglGetProcAddress("eglDestroyImageKHR");
	preview->image_texture = (void *)eglGetProcAddress("glEGLImageTargetTexture2DOES");
	if (!preview->native_client || !preview->create_image || !preview->destroy_image ||
	    !preview->image_texture)
		return false;

	vertex = compile_shader(GL_VERTEX_SHADER, vertex_source);
	fragment = compile_shader(GL_FRAGMENT_SHADER, fragment_source);
	if (!vertex || !fragment)
		goto fail;
	preview->program = glCreateProgram();
	glAttachShader(preview->program, vertex);
	glAttachShader(preview->program, fragment);
	glLinkProgram(preview->program);
	glGetProgramiv(preview->program, GL_LINK_STATUS, &linked);
	glDeleteShader(vertex);
	glDeleteShader(fragment);
	if (!linked) {
		char log[512] = {0};
		glGetProgramInfoLog(preview->program, sizeof(log) - 1, NULL, log);
		fprintf(stderr, "preview texture: shader link failed: %s\n", log);
		glDeleteProgram(preview->program);
		preview->program = 0;
		return false;
	}
	preview->position = glGetAttribLocation(preview->program, "position");
	preview->texcoord = glGetAttribLocation(preview->program, "texcoord");
	preview->sampler = glGetUniformLocation(preview->program, "frame");
	glGenTextures(1, &preview->external);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, preview->external);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	return glGetError() == GL_NO_ERROR;

fail:
	if (vertex)
		glDeleteShader(vertex);
	if (fragment)
		glDeleteShader(fragment);
	return false;
}

struct atl_preview_texture *atl_preview_texture_new(void)
{
	return calloc(1, sizeof(struct atl_preview_texture));
}

unsigned atl_preview_texture_update(struct atl_preview_texture *preview, void *hardware,
	                                int width, int height, int *out_width, int *out_height)
{
	static const GLfloat positions[] = {-1, -1, 1, -1, -1, 1, 1, 1};
	/* Android's SurfaceTexture transform flips the producer vertically. */
	static const GLfloat texcoords[] = {0, 1, 1, 1, 0, 0, 1, 0};
	EGLDisplay display;
	EGLContext context;
	EGLClientBuffer client;
	EGLImageKHR image;
	GLuint output = 0, framebuffer = 0;
	GLenum status;
	int long_side;

	if (!preview || preview->failed || !hardware || width < 1 || height < 1)
		return 0;
	display = eglGetCurrentDisplay();
	context = eglGetCurrentContext();
	if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT || !prepare(preview))
		goto fail;
	client = preview->native_client(hardware);
	image = client ? preview->create_image(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
	                                     client, NULL) : EGL_NO_IMAGE_KHR;
	if (image == EGL_NO_IMAGE_KHR)
		goto fail;

	glBindTexture(GL_TEXTURE_EXTERNAL_OES, preview->external);
	preview->image_texture(GL_TEXTURE_EXTERNAL_OES, (GLeglImageOES)image);
	if (glGetError() != GL_NO_ERROR) {
		preview->destroy_image(display, image);
		goto fail;
	}
	if (preview->image)
		preview->destroy_image(preview->display, preview->image);
	preview->image = image;
	preview->display = display;
	preview->context = context;

	long_side = width > height ? width : height;
	*out_width = long_side > PREVIEW_MAX ? width * PREVIEW_MAX / long_side : width;
	*out_height = long_side > PREVIEW_MAX ? height * PREVIEW_MAX / long_side : height;
	glGenTextures(1, &output);
	glBindTexture(GL_TEXTURE_2D, output);
	glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, *out_width, *out_height, 0,
	             GL_RGBA, GL_UNSIGNED_BYTE, NULL);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	glGenFramebuffers(1, &framebuffer);
	glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, output, 0);
	status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
	if (status != GL_FRAMEBUFFER_COMPLETE)
		goto draw_fail;

	glViewport(0, 0, *out_width, *out_height);
	glUseProgram(preview->program);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, preview->external);
	glUniform1i(preview->sampler, 0);
	glBindBuffer(GL_ARRAY_BUFFER, 0);
	glEnableVertexAttribArray(preview->position);
	glEnableVertexAttribArray(preview->texcoord);
	glVertexAttribPointer(preview->position, 2, GL_FLOAT, GL_FALSE, 0, positions);
	glVertexAttribPointer(preview->texcoord, 2, GL_FLOAT, GL_FALSE, 0, texcoords);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glDisableVertexAttribArray(preview->position);
	glDisableVertexAttribArray(preview->texcoord);
	glFlush();
	glDeleteFramebuffers(1, &framebuffer);
	if (glGetError() != GL_NO_ERROR)
		goto output_fail;
	return output;

draw_fail:
	if (framebuffer)
		glDeleteFramebuffers(1, &framebuffer);
output_fail:
	if (output)
		glDeleteTextures(1, &output);
fail:
	if (!preview->failed)
		fprintf(stderr, "preview texture: AHardwareBuffer import failed (EGL 0x%x, GL 0x%x)\n",
		        eglGetError(), glGetError());
	preview->failed = true;
	return 0;
}

void atl_preview_texture_free(struct atl_preview_texture *preview)
{
	if (!preview)
		return;
	if (preview->image && preview->destroy_image)
		preview->destroy_image(preview->display, preview->image);
	if (eglGetCurrentContext() == preview->context) {
		if (preview->external)
			glDeleteTextures(1, &preview->external);
		if (preview->program)
			glDeleteProgram(preview->program);
	}
	free(preview);
}
