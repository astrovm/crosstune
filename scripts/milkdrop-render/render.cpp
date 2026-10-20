// Draws projectM offline, frame by frame, the way the app does: 32x24 mesh, 3 s blends, no
// automatic switching. Reads raw f32le stereo 44.1 kHz audio and a cue list of "frame preset.milk"
// lines, and writes raw RGBA frames to stdout, bottom row first.
// Built and run by render.py, which also patches projectM's clock to advance 1/FPS a frame.
//   render <audio.f32> <cues.txt> <width> <height> <frames> <preroll frames>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <projectM-4/projectM.h>

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <map>
#include <random>
#include <string>
#include <vector>

#ifndef FPS
#define FPS 60
#endif

// projectM picks a preset's hue offsets and random textures with std::random_device. Drawn from
// $SEED instead, so the same song, presets and seed draw the same frames every time.
unsigned int std::random_device::_M_getval()
{
    static std::mt19937 numbers(getenv("SEED") ? strtoul(getenv("SEED"), nullptr, 10) : 1);
    return numbers();
}

int main(int argc, char** argv)
{
    if (argc != 7) {
        fprintf(stderr, "usage: render <audio.f32> <cues.txt> <width> <height> <frames> <preroll frames>\n");
        return 2;
    }
    const int width = atoi(argv[3]), height = atoi(argv[4]), frames = atoi(argv[5]), preroll = atoi(argv[6]);

    std::ifstream audio(argv[1], std::ios::binary | std::ios::ate);
    std::vector<float> pcm(size_t(audio.tellg()) / sizeof(float));
    audio.seekg(0);
    audio.read(reinterpret_cast<char*>(pcm.data()), pcm.size() * sizeof(float));

    std::map<int, std::string> cues;
    std::ifstream cueFile(argv[2]);
    for (std::string line; std::getline(cueFile, line);) {
        auto space = line.find(' ');
        if (space != std::string::npos) cues[atoi(line.substr(0, space).c_str())] = line.substr(space + 1);
    }
    if (cues.empty()) {
        fprintf(stderr, "no presets in %s\n", argv[2]);
        return 2;
    }

    // An offscreen OpenGL ES 3 context, no window needed.
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(display, nullptr, nullptr);
    const EGLint configAttributes[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
                                       EGL_ALPHA_SIZE, 8, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_NONE};
    EGLConfig config;
    EGLint configs = 0;
    if (!eglChooseConfig(display, configAttributes, &config, 1, &configs) || configs < 1) {
        fprintf(stderr, "no EGL config\n");
        return 1;
    }
    const EGLint surfaceAttributes[] = {EGL_WIDTH, width, EGL_HEIGHT, height, EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface(display, config, surfaceAttributes);
    // OpenGL ES, as on the phone: some presets' shaders only build there, and fall back to plainer ones otherwise.
    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint contextAttributes[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
    if (!eglMakeCurrent(display, surface, surface, context)) {
        fprintf(stderr, "no OpenGL ES 3 context\n");
        return 1;
    }

    projectm_handle projectm = projectm_create();
    if (!projectm) {
        fprintf(stderr, "projectM didn't start\n");
        return 1;
    }
    // Same as app/src/main/cpp/milkdrop.cpp, except the frame rate.
    projectm_set_window_size(projectm, width, height);
    projectm_set_mesh_size(projectm, 32, 24);
    projectm_set_fps(projectm, FPS);
    projectm_set_preset_locked(projectm, true);
    projectm_set_hard_cut_enabled(projectm, false);
    projectm_set_soft_cut_duration(projectm, 3.0);
    // The app's textures, from $TEXTURES, which render.py sets.
    if (const char* textures = getenv("TEXTURES")) projectm_set_texture_search_paths(projectm, &textures, 1);
    // projectM seeds rand() from the time; the presets' rand() goes the same way each run.
    srand(1);

    // The preroll fills projectM's feedback buffers with the first preset before frame 0.
    const int samplesPerFrame = 44100 / FPS;
    std::vector<unsigned char> pixels(size_t(width) * height * 4);
    projectm_load_preset_file(projectm, cues.begin()->second.c_str(), false);
    for (int frame = -preroll; frame < frames; frame++) {
        auto cue = cues.find(frame);
        if (frame > cues.begin()->first && cue != cues.end()) projectm_load_preset_file(projectm, cue->second.c_str(), true);
        const long sample = long(frame + preroll) * samplesPerFrame;
        if ((sample + samplesPerFrame) * 2 <= long(pcm.size())) {
            projectm_pcm_add_float(projectm, pcm.data() + sample * 2, samplesPerFrame, PROJECTM_STEREO);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
        projectm_opengl_render_frame(projectm);
        if (frame < 0) continue;
        // projectM leaves its own framebuffer bound for reading, which holds the frame before its final shader.
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
        fwrite(pixels.data(), 1, pixels.size(), stdout);
    }
    projectm_destroy(projectm);
    return 0;
}
