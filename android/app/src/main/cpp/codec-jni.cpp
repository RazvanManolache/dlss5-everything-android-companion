#include <jni.h>
#include <cstdint>
#include <vector>
#include "pyrowave-bridge.h"
static jbyteArray fail(JNIEnv *env) { env->ThrowNew(env->FindClass("java/io/IOException"), "Pyrowave frame conversion failed."); return nullptr; }
extern "C" JNIEXPORT jlong JNICALL Java_io_phoenixlink_NativeCodec_create(JNIEnv *, jclass, jint w, jint h, jint budget) {
    return reinterpret_cast<jlong>(pw_create(w, h, budget));
}
extern "C" JNIEXPORT jstring JNICALL Java_io_phoenixlink_NativeCodec_createError(JNIEnv *env, jclass) { return env->NewStringUTF(pw_create_error()); }
extern "C" JNIEXPORT void JNICALL Java_io_phoenixlink_NativeCodec_destroy(JNIEnv *, jclass, jlong context) { pw_destroy(reinterpret_cast<void *>(context)); }
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_phoenixlink_NativeCodec_encodeNative(JNIEnv *env, jclass, jlong context, jbyteArray input) {
    try {
        const auto length = env->GetArrayLength(input);
        if (length < 4 || length > 64 * 1024 * 1024) return fail(env);
        std::vector<uint8_t> source(length), output(16 * 1024 * 1024);
        env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(source.data()));
        if (env->ExceptionCheck()) return nullptr;
        int count = pw_encode(reinterpret_cast<void *>(context), source.data(), length, output.data(), int(output.size()));
        if (count <= 0) return fail(env);
        auto result = env->NewByteArray(count); if (!result) return nullptr;
        env->SetByteArrayRegion(result, 0, count, reinterpret_cast<jbyte *>(output.data())); return result;
    } catch (...) { return fail(env); }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_phoenixlink_NativeCodec_decodeNative(JNIEnv *env, jclass, jlong context, jbyteArray input, jint rgbaBytes) {
    try {
        const auto length = env->GetArrayLength(input);
        if (length < 8 || length > 16 * 1024 * 1024 || rgbaBytes < 4 || rgbaBytes > 64 * 1024 * 1024) return fail(env);
        std::vector<uint8_t> source(length), output(rgbaBytes);
        env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(source.data()));
        if (env->ExceptionCheck()) return nullptr;
        if (pw_decode(reinterpret_cast<void *>(context), source.data(), length, output.data(), rgbaBytes) != rgbaBytes) return fail(env);
        auto result = env->NewByteArray(rgbaBytes); if (!result) return nullptr;
        env->SetByteArrayRegion(result, 0, rgbaBytes, reinterpret_cast<jbyte *>(output.data())); return result;
    } catch (...) { return fail(env); }
}
