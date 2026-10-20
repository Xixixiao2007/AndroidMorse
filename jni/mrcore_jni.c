/* JNI shim: Kotlin's MorseCore -> the Pascal contest core (libmrcore.so).
   The Pascal side exposes plain C entry points (docs/abi.md); this file is the only place that
   knows about JNI, so the core stays testable and the UI stays free of native plumbing. */
#include <jni.h>

extern const char *mr_version(void);
extern int mr_contest_count(void);
extern int mr_contest_name(int idx, char *buf, int cap);
extern int mr_contest_key(int idx, char *buf, int cap);

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
