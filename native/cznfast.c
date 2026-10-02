/* CZN zstd JNI 壳：NDK(bionic) 编译，供 Shizuku 服务进程加载。
 * 仅暴露 compress / decompress 两个最小接口。 */
#include <jni.h>
#include <stdlib.h>
#include "zstd.h"

JNIEXPORT jbyteArray JNICALL
Java_yporoc_czntoolkit_patcher_engine_CznZstd_nativeCompress(JNIEnv *env, jclass clazz,
                                                          jbyteArray src, jint level) {
    jsize len = (*env)->GetArrayLength(env, src);
    jbyte *p = (*env)->GetByteArrayElements(env, src, NULL);
    size_t bound = ZSTD_compressBound((size_t) len);
    jbyte *out = (jbyte *) malloc(bound);
    if (out == NULL) {
        (*env)->ReleaseByteArrayElements(env, src, p, JNI_ABORT);
        return NULL;
    }
    size_t r = ZSTD_compress(out, bound, p, (size_t) len, (int) level);
    (*env)->ReleaseByteArrayElements(env, src, p, JNI_ABORT);
    if (ZSTD_isError(r)) {
        free(out);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize) r);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize) r, out);
    free(out);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_yporoc_czntoolkit_patcher_engine_CznZstd_nativeDecompress(JNIEnv *env, jclass clazz,
                                                            jbyteArray src, jint maxOut) {
    jsize len = (*env)->GetArrayLength(env, src);
    jbyte *p = (*env)->GetByteArrayElements(env, src, NULL);
    jbyte *out = (jbyte *) malloc((size_t) maxOut);
    if (out == NULL) {
        (*env)->ReleaseByteArrayElements(env, src, p, JNI_ABORT);
        return NULL;
    }
    size_t r = ZSTD_decompress(out, (size_t) maxOut, p, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, src, p, JNI_ABORT);
    if (ZSTD_isError(r)) {
        free(out);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize) r);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize) r, out);
    free(out);
    return result;
}
