#include "gl_renderer.h"

#include <android/log.h>

#define GL_LOG_TAG "SunshineRot"

#define GL_LOG(...) __android_log_print(ANDROID_LOG_INFO, GL_LOG_TAG, __VA_ARGS__)
#define GL_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, GL_LOG_TAG, __VA_ARGS__)

namespace gl_render {

    namespace {

        const char *VERTEX_SHADER =
                "attribute vec4 a_position;\n"
                "attribute vec4 a_texcoord;\n"
                "varying vec2 v_texcoord;\n"
                "void main() {\n"
                "    gl_Position = a_position;\n"
                "    v_texcoord = a_texcoord.xy;\n"
                "}\n";

        // SamplerExternalOES needs the extension pragma on GLES2
        // Rotation is applied directly to uv with explicit branches: mat3
        // column-major semantics are easy to get wrong, and a silent transpose
        // samples outside the image (visible as a flat colour).
        const char *FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n"
                "precision mediump float;\n"
                "uniform samplerExternalOES u_texture;\n"
                "uniform mat4 u_tex_matrix;\n"
                "uniform int u_rotation_deg;\n"
                "varying vec2 v_texcoord;\n"
                "void main() {\n"
                "    vec2 uv = v_texcoord;\n"
                "    if (u_rotation_deg == 90) {\n"
                "        uv = vec2(v_texcoord.y, 1.0 - v_texcoord.x);\n"
                "    } else if (u_rotation_deg == 180) {\n"
                "        uv = vec2(1.0 - v_texcoord.x, 1.0 - v_texcoord.y);\n"
                "    } else if (u_rotation_deg == 270) {\n"
                "        uv = vec2(1.0 - v_texcoord.y, v_texcoord.x);\n"
                "    }\n"
                "    gl_FragColor = texture2D(u_texture, (u_tex_matrix * vec4(uv, 0.0, 1.0)).xy);\n"
                "}\n";

        const int FLOATS_PER_VERTEX = 4;

        /**
         * Quad in NDC. Rotation happens in texture space (shader), so the
         * geometry only needs to carry the aspect-ratio fit: for a 900x1600
         * portrait capture rotated 90 degrees the fitted box is 16:9 and
         * exactly fills a 1280x720 encoder surface.
         */
        void build_quad(float sx, float sy, float out[16]) {
            const float quad[] = {
                    // x,   y,    u,    v
                    -sx, -sy, 0.0f, 0.0f,
                    sx, -sy, 1.0f, 0.0f,
                    -sx, sy, 0.0f, 1.0f,
                    sx, sy, 1.0f, 1.0f,
            };
            for (int i = 0; i < 16; i++) {
                out[i] = quad[i];
            }
        }

        /**
         * Visible source aspect after rotation: rotating by 90 or 270 swaps
         * the axes. Returns scale factors for the quad so the image fills the
         * encoder frame without distortion.
         */
        void compute_fit(int rotation, float src_w, float src_h,
                         float dst_w, float dst_h,
                         float &scale_x, float &scale_y) {
            float visible_w = (rotation == 90 || rotation == 270) ? src_h : src_w;
            float visible_h = (rotation == 90 || rotation == 270) ? src_w : src_h;

            float src_aspect = visible_w / visible_h;
            float dst_aspect = dst_w / dst_h;

            if (src_aspect > dst_aspect) {
                // Wider than target: full width, letterbox top/bottom
                scale_x = 1.0f;
                scale_y = dst_aspect / src_aspect;
            } else {
                scale_x = src_aspect / dst_aspect;
                scale_y = 1.0f;
            }
        }

        GLuint compile_shader(GLenum type, const char *source) {
            GLuint shader = glCreateShader(type);
            if (shader == 0) {
                GL_LOGE("glCreateShader failed");
                return 0;
            }
            glShaderSource(shader, 1, &source, nullptr);
            glCompileShader(shader);

            GLint compiled = 0;
            glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
            if (!compiled) {
                GLint length = 0;
                glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
                if (length > 0) {
                    char *log = new char[length];
                    glGetShaderInfoLog(shader, length, nullptr, log);
                    GL_LOGE("shader compile failed: %s", log);
                    delete[] log;
                }
                glDeleteShader(shader);
                return 0;
            }
            return shader;
        }

        GLuint build_program() {
            GLuint vs = compile_shader(GL_VERTEX_SHADER, VERTEX_SHADER);
            if (vs == 0) {
                return 0;
            }
            GLuint fs = compile_shader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            if (fs == 0) {
                glDeleteShader(vs);
                return 0;
            }

            GLuint program = glCreateProgram();
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            glBindAttribLocation(program, 0, "a_position");
            glBindAttribLocation(program, 1, "a_texcoord");
            glLinkProgram(program);

            glDeleteShader(vs);
            glDeleteShader(fs);

            GLint linked = 0;
            glGetProgramiv(program, GL_LINK_STATUS, &linked);
            if (!linked) {
                GLint length = 0;
                glGetProgramiv(program, GL_INFO_LOG_LENGTH, &length);
                if (length > 0) {
                    char *log = new char[length];
                    glGetProgramInfoLog(program, length, nullptr, log);
                    GL_LOGE("program link failed: %s", log);
                    delete[] log;
                }
                glDeleteProgram(program);
                return 0;
            }
            return program;
        }

        bool make_current(renderer_t &r) {
            if (eglMakeCurrent(r.display, r.surface, r.surface, r.context) != EGL_TRUE) {
                GL_LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
                return false;
            }
            return true;
        }

        /**
         * The angle describes how the image is turned clockwise on screen.
         * The shader applies it to the sampling coordinates directly.
         */

    } // namespace

    renderer_t create(ANativeWindow *target,
                      int src_width, int src_height,
                      int dst_width, int dst_height,
                      int rotation_degrees) {
        renderer_t r;
        r.src_width = src_width;
        r.src_height = src_height;
        r.dst_width = dst_width;
        r.dst_height = dst_height;
        r.rotation_degrees = ((rotation_degrees % 360) + 360) % 360;

        if (r.rotation_degrees % 90 != 0) {
            GL_LOGE("rotation %d is not a multiple of 90, using 0", r.rotation_degrees);
            r.rotation_degrees = 0;
        }

        r.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (r.display == EGL_NO_DISPLAY) {
            GL_LOGE("eglGetDisplay failed");
            return r;
        }
        if (eglInitialize(r.display, nullptr, nullptr) != EGL_TRUE) {
            GL_LOGE("eglInitialize failed: 0x%x", eglGetError());
            return r;
        }

        // EGL_RECORDABLE_ANDROID is mandatory when the EGL surface is the input
        // of a hardware MediaCodec encoder. Software encoders (what an emulator
        // provides) ignore it, which is why this worked there but produced black
        // frames on real hardware.
        //
        // Choose the config explicitly instead of trusting eglChooseConfig's
        // ordering: on some drivers the returned "best" config has
        // EGL_RECORDABLE_ANDROID = 0 even though the attribute was requested.
        const EGLint config_attribs[] = {
                EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL_RED_SIZE, 8,
                EGL_GREEN_SIZE, 8,
                EGL_BLUE_SIZE, 8,
                EGL_ALPHA_SIZE, 8,
                EGL_RECORDABLE_ANDROID, 1,
                EGL_NONE
        };

        EGLint num_configs = 0;
        EGLConfig candidates[64];
        if (eglChooseConfig(r.display, config_attribs, candidates, 64, &num_configs) != EGL_TRUE) {
            num_configs = 0;
        }

        int recordable_count = 0;
        for (EGLint i = 0; i < num_configs; i++) {
            EGLint recordable = 0;
            if (eglGetConfigAttrib(r.display, candidates[i], EGL_RECORDABLE_ANDROID, &recordable) &&
                recordable) {
                r.config = candidates[i];
                recordable_count++;
                break;
            }
        }

        if (r.config == nullptr) {
            // No recordable config: fall back so software-encoder devices still
            // render, and say so loudly because hardware encoders need it.
            GL_LOGE("no EGL config with EGL_RECORDABLE_ANDROID (checked %d), "
                    "hardware encoders may output black frames", (int) num_configs);
            const EGLint fallback_attribs[] = {
                    EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL_RED_SIZE, 8,
                    EGL_GREEN_SIZE, 8,
                    EGL_BLUE_SIZE, 8,
                    EGL_ALPHA_SIZE, 8,
                    EGL_NONE
            };
            if (eglChooseConfig(r.display, fallback_attribs, &r.config, 1, &num_configs) != EGL_TRUE ||
                num_configs < 1) {
                GL_LOGE("eglChooseConfig failed: 0x%x", eglGetError());
                destroy(r);
                return r;
            }
        } else {
            GL_LOG("picked recordable EGL config (%d of %d candidates)",
                   recordable_count, (int) num_configs);
        }

        const EGLint context_attribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
        r.context = eglCreateContext(r.display, r.config, EGL_NO_CONTEXT, context_attribs);
        if (r.context == EGL_NO_CONTEXT) {
            GL_LOGE("eglCreateContext failed: 0x%x", eglGetError());
            destroy(r);
            return r;
        }

        r.surface = eglCreateWindowSurface(r.display, r.config, target, nullptr);
        if (r.surface == EGL_NO_SURFACE) {
            GL_LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
            destroy(r);
            return r;
        }

        if (!make_current(r)) {
            destroy(r);
            return r;
        }

        glViewport(0, 0, dst_width, dst_height);

        r.program = build_program();
        if (r.program == 0) {
            GL_LOGE("failed to build shader program");
            destroy(r);
            return r;
        }

        glUseProgram(r.program);
        glUniform1i(glGetUniformLocation(r.program, "u_texture"), 0);
        r.u_tex_matrix = glGetUniformLocation(r.program, "u_tex_matrix");
        r.u_rotation = glGetUniformLocation(r.program, "u_rotation_deg");

        glGenBuffers(1, &r.vbo);
        glBindBuffer(GL_ARRAY_BUFFER, r.vbo);
        glBufferData(GL_ARRAY_BUFFER, 16 * sizeof(float), nullptr, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        // External OES texture; the SurfaceTexture created on this thread will
        // write its frames into this name.
        glGenTextures(1, &r.texture);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, r.texture);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

        GLenum gl_error = glGetError();
        if (gl_error != GL_NO_ERROR) {
            GL_LOGE("GL error during setup: 0x%x", gl_error);
            destroy(r);
            return r;
        }

        r.valid = true;
        {
            EGLint recordable = 0;
            eglGetConfigAttrib(r.display, r.config, EGL_RECORDABLE_ANDROID, &recordable);
            GL_LOG("renderer ready: src=%dx%d dst=%dx%d rotation=%d recordable=%d",
                   src_width, src_height, dst_width, dst_height, r.rotation_degrees,
                   (int) recordable);
        }
        return r;
    }

    void draw(renderer_t &r, const float tex_matrix[16]) {
        if (!r.valid || r.texture == 0) {
            return;
        }
        if (!make_current(r)) {
            return;
        }

        float scale_x = 1.0f;
        float scale_y = 1.0f;
        compute_fit(r.rotation_degrees,
                    static_cast<float>(r.src_width), static_cast<float>(r.src_height),
                    static_cast<float>(r.dst_width), static_cast<float>(r.dst_height),
                    scale_x, scale_y);

        float quad[16];
        build_quad(scale_x, scale_y, quad);

        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);

        glUseProgram(r.program);
        glUniformMatrix4fv(r.u_tex_matrix, 1, GL_FALSE, tex_matrix);
        glUniform1i(r.u_rotation, r.rotation_degrees);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, r.texture);

        glBindBuffer(GL_ARRAY_BUFFER, r.vbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_DYNAMIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, FLOATS_PER_VERTEX * sizeof(float),
                              (const void *) nullptr);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, FLOATS_PER_VERTEX * sizeof(float),
                              (const void *) (2 * sizeof(float)));

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        GLenum draw_error = glGetError();
        if (draw_error != GL_NO_ERROR && r.draw_count < 3) {
            GL_LOGE("GL error after draw #%d: 0x%x", (int) r.draw_count, draw_error);
        }
        if (r.draw_count < 3) {
            // Read back what we actually rendered: a black frame with a valid
            // texture means the capture texture never received pixels, while a
            // black read-back with non-black texture points at the draw path.
            unsigned char center[4] = {0, 0, 0, 0};
            glReadPixels(r.dst_width / 2, r.dst_height / 2, 1, 1,
                         GL_RGBA, GL_UNSIGNED_BYTE, center);
            GLenum read_error = glGetError();
            GL_LOG("draw #%d: tex=%u quad=(%.3f,%.3f) rot=%d centerPixel=(%u,%u,%u,%u) "
                   "readErr=0x%x",
                   (int) r.draw_count, (unsigned) r.texture, scale_x, scale_y,
                   r.rotation_degrees, center[0], center[1], center[2], center[3],
                   read_error);
        }
        r.draw_count++;

        if (eglSwapBuffers(r.display, r.surface) != EGL_TRUE) {
            GL_LOGE("eglSwapBuffers failed: 0x%x", eglGetError());
        }
    }

    void destroy(renderer_t &r) {
        if (r.display != EGL_NO_DISPLAY) {
            eglMakeCurrent(r.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        if (r.vbo != 0) {
            glDeleteBuffers(1, &r.vbo);
            r.vbo = 0;
        }
        if (r.texture != 0) {
            glDeleteTextures(1, &r.texture);
            r.texture = 0;
        }
        if (r.program != 0) {
            glDeleteProgram(r.program);
            r.program = 0;
        }
        if (r.display != EGL_NO_DISPLAY) {
            if (r.surface != EGL_NO_SURFACE) {
                eglDestroySurface(r.display, r.surface);
            }
            if (r.context != EGL_NO_CONTEXT) {
                eglDestroyContext(r.display, r.context);
            }
            eglTerminate(r.display);
        }
        r.surface = EGL_NO_SURFACE;
        r.context = EGL_NO_CONTEXT;
        r.display = EGL_NO_DISPLAY;
        r.valid = false;
    }

} // namespace gl_render
