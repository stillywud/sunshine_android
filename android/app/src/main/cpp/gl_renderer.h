#pragma once

#include <android/native_window.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

/**
 * EGL/GLES2 renderer that rotates MediaProjection output before it reaches the
 * H.264 encoder input surface.
 *
 * Pipeline:
 *   MediaProjection -> VirtualDisplay(SurfaceTexture) -> external OES texture
 *                   -> this renderer (rotate + fit) -> encoder InputSurface
 *
 * The VirtualDisplay is created at the *source* screen resolution, so Android
 * never letterboxes the capture. Rotation and aspect fitting happen here, in
 * the encoder's target resolution.
 */
namespace gl_render {

    struct renderer_t {
        EGLDisplay display = EGL_NO_DISPLAY;
        EGLContext context = EGL_NO_CONTEXT;
        EGLSurface surface = EGL_NO_SURFACE;
        EGLConfig config = nullptr;

        GLuint program = 0;
        GLuint vbo = 0;
        GLuint texture = 0; // external OES texture owned by the SurfaceTexture

        GLint u_tex_matrix = -1;
        GLint u_rotation = -1;

        int src_width = 0;
        int src_height = 0;
        int dst_width = 0;
        int dst_height = 0;
        int rotation_degrees = 0;

        bool valid = false;
    };

    /**
     * Create the EGL context that renders into @p target (the encoder input
     * surface), the shader program and the external OES texture that the
     * SurfaceTexture created on this thread will fill.
     *
     * @param target           encoder input ANativeWindow
     * @param src_width        capture resolution width  (logical screen size)
     * @param src_height       capture resolution height
     * @param dst_width        encoder resolution width  (requested by the client)
     * @param dst_height       encoder resolution height
     * @param rotation_degrees image rotation applied in the shader (0/90/180/270)
     */
    renderer_t create(ANativeWindow *target,
                      int src_width, int src_height,
                      int dst_width, int dst_height,
                      int rotation_degrees);

    /** Draw one frame. tex_matrix comes from SurfaceTexture.getTransformMatrix(). */
    void draw(renderer_t &renderer, const float tex_matrix[16]);

    /** Release every EGL/GL resource held by the renderer. */
    void destroy(renderer_t &renderer);

} // namespace gl_render
