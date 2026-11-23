// JNI bridge between WhisperNative.kt and whisper.cpp (spec §2.1 as amended 11 November
// 2026). Built by tools/whisper-build.sh into libwhisper_jni.so; see
// docs/audio-and-speech.md, "Speech recognition".
//
// Rules this file keeps:
// * Nothing is written anywhere. whisper.cpp's and ggml's log output is dropped before any
//   model is read, so neither a file name nor a transcript can reach logcat.
// * The model is read through the file descriptor Kotlin opened from the operator's
//   Storage Access Framework grant. This file never opens a path and never closes that
//   descriptor; the caller closes it once load returns.
// * One context is used by one call at a time; WhisperNative serialises the calls.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <new>
#include <string>
#include <vector>

#include <unistd.h>

#include "ggml.h"
#include "whisper.h"

namespace {

// Mirrors WhisperNative.kt's SAMPLE_RATE / MAX_SAMPLES.
constexpr int kSampleRate = WHISPER_SAMPLE_RATE;
constexpr int kMaxSamples = 30 * kSampleRate;

struct Engine {
    whisper_context *ctx = nullptr;
    std::atomic<bool> cancel{false};
};

struct FdReader {
    int fd;
    bool eof;
    bool failed;
    uint64_t bytes;
    int reads_after_eof;
};

// whisper.cpp checks only the magic before it sizes its allocations from the header, and
// treats a file with no tensors as an empty test model. Both are refused here first: a
// picked file that is not a real model must fail to load, not crash or load hollow.
constexpr uint32_t kMagic = 0x67676d6c;  // "ggml", GGML_FILE_MAGIC
constexpr uint64_t kMinModelBytes = 2u * 1024 * 1024;

bool plausible_header(int fd) {
    int32_t h[12];
    off_t start = lseek(fd, 0, SEEK_CUR);
    if (start < 0) return false;
    ssize_t n = pread(fd, h, sizeof(h), start);
    if (n != static_cast<ssize_t>(sizeof(h))) return false;
    if (static_cast<uint32_t>(h[0]) != kMagic) return false;
    const int32_t n_vocab = h[1], audio_ctx = h[2], audio_state = h[3], audio_head = h[4],
                  audio_layer = h[5], text_ctx = h[6], text_state = h[7], text_head = h[8],
                  text_layer = h[9], n_mels = h[10], ftype = h[11];
    if (n_vocab < 51864 || n_vocab > 51866) return false;  // Whisper's English and multilingual vocabularies
    if (audio_ctx != 1500 || text_ctx != 448) return false;
    if (n_mels != 80 && n_mels != 128) return false;
    if (audio_state != text_state || audio_state < 8 || audio_state > 4096) return false;
    for (int32_t heads : {audio_head, text_head}) {
        if (heads < 1 || heads > 64 || audio_state % heads != 0) return false;
    }
    if (audio_layer < 1 || audio_layer > 64 || text_layer < 1 || text_layer > 64) return false;
    // GGML_QNT_VERSION_FACTOR is 1000. An all-F32 model (ftype 0) is refused: ggml's
    // im2col for the encoder's convolutions asserts F16 kernels and would abort the app.
    return ftype > 0 && ftype % 1000 > 0 && ftype % 1000 <= 64;
}

size_t fd_read(void *self, void *output, size_t size) {
    auto *reader = static_cast<FdReader *>(self);
    auto *out = static_cast<uint8_t *>(output);
    if (reader->eof) reader->reads_after_eof++;
    size_t done = 0;
    while (done < size) {
        ssize_t n = read(reader->fd, out + done, size - done);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) {
            reader->failed = true;
            break;
        }
        if (n == 0) {
            reader->eof = true;
            break;
        }
        done += static_cast<size_t>(n);
        reader->bytes += static_cast<uint64_t>(n);
    }
    // A short read leaves the rest of the caller's buffer as zeros, never stale memory.
    if (done < size) std::memset(out + done, 0, size - done);
    return done;
}

bool fd_eof(void *self) {
    auto *reader = static_cast<FdReader *>(self);
    return reader->eof || reader->failed;
}

void fd_close(void *) {
    // The descriptor belongs to the caller.
}

void drop_log(enum ggml_log_level, const char *, void *) {}

bool abort_requested(void *self) {
    return static_cast<Engine *>(self)->cancel.load(std::memory_order_relaxed);
}

Engine *engine_of(jlong handle) {
    return reinterpret_cast<Engine *>(static_cast<intptr_t>(handle));
}

jstring utf8_string(JNIEnv *env, const std::string &text) {
    // NewStringUTF wants modified UTF-8; go through a Java byte array so any byte
    // sequence whisper.cpp emits (including a token split mid-character) decodes safely.
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()),
                            reinterpret_cast<const jbyte *>(text.data()));
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    if (ctor == nullptr || charset == nullptr) return nullptr;
    auto result = static_cast<jstring>(env->NewObject(string_class, ctor, bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(string_class);
    return result;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_jw6ventures_headlink_WhisperNative_nativeQuiet(JNIEnv *, jobject) {
    whisper_log_set(drop_log, nullptr);
}

// Reads a whole model from [fd] (current position onwards). Returns a handle, or 0 when
// the file is not a model whisper.cpp can load (wrong magic, cut short, unreadable).
JNIEXPORT jlong JNICALL
Java_com_jw6ventures_headlink_WhisperNative_nativeLoad(JNIEnv *, jobject, jint fd) {
    whisper_log_set(drop_log, nullptr);
    if (!plausible_header(fd)) return 0;
    FdReader reader{fd, false, false, 0, 0};
    whisper_model_loader loader{};
    loader.context = &reader;
    loader.read = fd_read;
    loader.eof = fd_eof;
    loader.close = fd_close;
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = false;
    whisper_context *ctx = nullptr;
    try {
        // A damaged file can ask for an absurd allocation; that is a refusal, not a crash.
        ctx = whisper_init_with_params(&loader, params);
    } catch (...) {
        ctx = nullptr;
    }
    if (ctx == nullptr) return 0;
    // A whole model ends exactly where whisper.cpp's tensor loop asks for the next
    // tensor's three header words; a file that ran out earlier keeps being read past its end.
    if (reader.failed || reader.reads_after_eof > 2 || reader.bytes < kMinModelBytes) {
        whisper_free(ctx);
        return 0;
    }
    auto *engine = new (std::nothrow) Engine();
    if (engine == nullptr) {
        whisper_free(ctx);
        return 0;
    }
    engine->ctx = ctx;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(engine));
}

// "<size> <en|multi>", for instance "base en"; the size is whisper.cpp's own name.
JNIEXPORT jstring JNICALL
Java_com_jw6ventures_headlink_WhisperNative_nativeDescribe(JNIEnv *env, jobject, jlong handle) {
    Engine *engine = engine_of(handle);
    if (engine == nullptr) return nullptr;
    std::string text = whisper_model_type_readable(engine->ctx);
    text += whisper_is_multilingual(engine->ctx) ? " multi" : " en";
    return env->NewStringUTF(text.c_str());
}

// Transcribes [count] 16 kHz mono 16-bit samples. Returns the segments joined, or null when
// whisper.cpp failed or the call was cancelled (WhisperNative tells the two apart).
JNIEXPORT jstring JNICALL
Java_com_jw6ventures_headlink_WhisperNative_nativeTranscribe(
        JNIEnv *env, jobject, jlong handle, jshortArray samples, jint count, jstring prompt,
        jstring language, jint threads) {
    Engine *engine = engine_of(handle);
    if (engine == nullptr || samples == nullptr) return nullptr;
    jsize available = env->GetArrayLength(samples);
    if (count < 0 || count > available || count > kMaxSamples) return nullptr;

    std::vector<float> audio(static_cast<size_t>(count));
    {
        std::vector<jshort> pcm(static_cast<size_t>(count));
        env->GetShortArrayRegion(samples, 0, count, pcm.data());
        for (jsize i = 0; i < count; i++) audio[i] = static_cast<float>(pcm[i]) / 32768.0f;
        std::fill(pcm.begin(), pcm.end(), 0);
    }

    std::string prompt_text;
    if (prompt != nullptr) {
        const char *chars = env->GetStringUTFChars(prompt, nullptr);
        if (chars != nullptr) {
            prompt_text = chars;
            env->ReleaseStringUTFChars(prompt, chars);
        }
    }
    std::string language_text = "en";
    if (language != nullptr) {
        const char *chars = env->GetStringUTFChars(language, nullptr);
        if (chars != nullptr) {
            language_text = chars;
            env->ReleaseStringUTFChars(language, chars);
        }
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1, static_cast<int>(threads));
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.language = language_text.c_str();
    params.detect_language = false;
    params.initial_prompt = prompt_text.empty() ? nullptr : prompt_text.c_str();
    params.abort_callback = abort_requested;
    params.abort_callback_user_data = engine;

    engine->cancel.store(false, std::memory_order_relaxed);
    int status;
    try {
        status = whisper_full(engine->ctx, params, audio.data(), count);
    } catch (...) {
        status = -1;
    }
    std::fill(audio.begin(), audio.end(), 0.0f);
    if (status != 0 || engine->cancel.load(std::memory_order_relaxed)) return nullptr;

    std::string text;
    int segments = whisper_full_n_segments(engine->ctx);
    for (int i = 0; i < segments; i++) {
        const char *segment = whisper_full_get_segment_text(engine->ctx, i);
        if (segment != nullptr) text += segment;
    }
    jstring result = utf8_string(env, text);
    std::fill(text.begin(), text.end(), '\0');
    return result;
}

// Asks a running transcription to stop. Safe from any thread while the handle is alive.
JNIEXPORT void JNICALL
Java_com_jw6ventures_headlink_WhisperNative_nativeCancel(JNIEnv *, jobject, jlong handle) {
    Engine *engine = engine_of(handle);
    if (engine != nullptr) engine->cancel.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_jw6ventures_headlink_WhisperNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    Engine *engine = engine_of(handle);
    if (engine == nullptr) return;
    whisper_free(engine->ctx);
    delete engine;
}

JNIEXPORT jstring JNICALL
Java_com_jw6ventures_headlink_WhisperNative_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_version());
}

}  // extern "C"
