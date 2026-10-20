/* JNI shim: Kotlin's MorseCore -> the Pascal contest core (libmrcore.so).
   The Pascal side exposes plain C entry points (docs/abi.md); this file is the only place that
   knows about JNI, so the core stays testable and the UI stays free of native plumbing. */
#include <jni.h>
#include <stdint.h>

extern const char *mr_version(void);
extern int mr_contest_count(void);
extern int mr_contest_name(int idx, char *buf, int cap);
extern int mr_contest_key(int idx, char *buf, int cap);
extern void *mr_contest_start(int idx);
extern int mr_contest_class(void *h, char *buf, int cap);
extern void mr_contest_free(void *h);

JNIEXPORT jstring JNICALL
Java_com_bh4gzk_morserunner_MorseCore_version(JNIEnv *env, jclass cls)
{
    (void)cls;
    return (*env)->NewStringUTF(env, mr_version());
}

JNIEXPORT jint JNICALL
Java_com_bh4gzk_morserunner_MorseCore_contestCount(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)mr_contest_count();
}

JNIEXPORT jstring JNICALL
Java_com_bh4gzk_morserunner_MorseCore_contestName(JNIEnv *env, jclass cls, jint idx)
{
    char buf[512];
    (void)cls;
    if (mr_contest_name((int)idx, buf, (int)sizeof(buf)) < 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_bh4gzk_morserunner_MorseCore_contestKey(JNIEnv *env, jclass cls, jint idx)
{
    char buf[512];
    (void)cls;
    if (mr_contest_key((int)idx, buf, (int)sizeof(buf)) < 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

/* Hand the core a contest index and keep the object alive across calls; the Java side holds the
   pointer as a long and must hand it back to contestFree. */
JNIEXPORT jlong JNICALL
Java_com_bh4gzk_morserunner_MorseCore_contestStart(JNIEnv *env, jclass cls, jint idx)
{
    (void)env; (void)cls;
    return (jlong)(intptr_t)mr_contest_start((int)idx);
}

JNIEXPORT jstring JNICALL
Java_com_bh4gzk_morserunner_MorseCore_contestClass(JNIEnv *env, jclass cls, jlong h)
{
    char buf[256];
    (void)cls;
    if (mr_contest_class((void *)(intptr_t)h, buf, (int)sizeof(buf)) < 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT void JNICALL
Java_com_bh4gzk_morserunner_MorseCore_contestFree(JNIEnv *env, jclass cls, jlong h)
{
    (void)env; (void)cls;
    mr_contest_free((void *)(intptr_t)h);
}
