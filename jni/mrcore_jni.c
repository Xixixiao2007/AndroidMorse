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

/* ---- running a contest -------------------------------------------------- */
extern void *mr_run_start(int idx, const char *mycall);
extern int mr_block(void *h, float *out, int cap);
extern void mr_send(void *h, int msg);
extern int mr_contest_rate(void);
extern int mr_block_size(void);
extern int mr_log_count(void);
extern int mr_log_cell(int row, int col, char *buf, int cap);
extern int mr_score(int which);

JNIEXPORT jlong JNICALL
Java_com_bh4gzk_morserunner_MorseCore_runStart(JNIEnv *env, jclass cls, jint idx, jstring call)
{
    const char *c;
    void *h;
    (void)cls;
    c = (call == NULL) ? NULL : (*env)->GetStringUTFChars(env, call, NULL);
    h = mr_run_start((int)idx, c);
    if (c != NULL) (*env)->ReleaseStringUTFChars(env, call, c);
    return (jlong)(intptr_t)h;
}

JNIEXPORT jint JNICALL
Java_com_bh4gzk_morserunner_MorseCore_block(JNIEnv *env, jclass cls, jlong h, jfloatArray buf)
{
    jfloat *p;
    jint n;
    (void)cls;
    if (buf == NULL) return 0;
    p = (*env)->GetFloatArrayElements(env, buf, NULL);
    if (p == NULL) return 0;
    n = (jint)mr_block((void *)(intptr_t)h, (float *)p, (*env)->GetArrayLength(env, buf));
    (*env)->ReleaseFloatArrayElements(env, buf, p, 0);
    return n;
}

JNIEXPORT void JNICALL
Java_com_bh4gzk_morserunner_MorseCore_send(JNIEnv *env, jclass cls, jlong h, jint msg)
{
    (void)env; (void)cls;
    mr_send((void *)(intptr_t)h, (int)msg);
}

JNIEXPORT jint JNICALL
Java_com_bh4gzk_morserunner_MorseCore_rate(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)mr_contest_rate();
}

JNIEXPORT jint JNICALL
Java_com_bh4gzk_morserunner_MorseCore_blockSize(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)mr_block_size();
}

JNIEXPORT jint JNICALL
Java_com_bh4gzk_morserunner_MorseCore_logCount(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)mr_log_count();
}

JNIEXPORT jstring JNICALL
Java_com_bh4gzk_morserunner_MorseCore_logCell(JNIEnv *env, jclass cls, jint row, jint col)
{
    char buf[256];
    (void)cls;
    if (mr_log_cell((int)row, (int)col, buf, (int)sizeof(buf)) < 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jint JNICALL
Java_com_bh4gzk_morserunner_MorseCore_score(JNIEnv *env, jclass cls, jint which)
{
    (void)env; (void)cls;
    return (jint)mr_score((int)which);
}

/* 0 = the QSO rate, 1 = the run clock, 2 = the Pile-Up caption: the three panel texts the game
   loop itself writes, read back so the phone shows the rate the core computed. */
extern int mr_status_text(int which, char *buf, int cap);

JNIEXPORT jstring JNICALL
Java_com_bh4gzk_morserunner_MorseCore_statusText(JNIEnv *env, jclass cls, jint which)
{
    char buf[128];
    (void)cls;
    if (mr_status_text((int)which, buf, (int)sizeof(buf)) < 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

extern void mr_set_his_call(void *h, const char *text);
extern void mr_set_data_dir(const char *path);

JNIEXPORT void JNICALL
Java_com_bh4gzk_morserunner_MorseCore_setHisCall(JNIEnv *env, jclass cls, jlong h, jstring text)
{
    const char *c;
    (void)cls;
    if (text == NULL) return;
    c = (*env)->GetStringUTFChars(env, text, NULL);
    if (c != NULL) {
        mr_set_his_call((void *)(intptr_t)h, c);
        (*env)->ReleaseStringUTFChars(env, text, c);
    }
}

JNIEXPORT void JNICALL
Java_com_bh4gzk_morserunner_MorseCore_setDataDir(JNIEnv *env, jclass cls, jstring path)
{
    const char *c;
    (void)cls;
    if (path == NULL) return;
    c = (*env)->GetStringUTFChars(env, path, NULL);
    if (c != NULL) {
        mr_set_data_dir(c);
        (*env)->ReleaseStringUTFChars(env, path, c);
    }
}

extern int mr_station_count(void *h);

JNIEXPORT jint JNICALL
Java_com_bh4gzk_morserunner_MorseCore_stationCount(JNIEnv *env, jclass cls, jlong h)
{
    (void)env; (void)cls;
    return (jint)mr_station_count((void *)(intptr_t)h);
}

extern int mr_debug_state(void *h, int which);

JNIEXPORT jint JNICALL
Java_com_bh4gzk_morserunner_MorseCore_debugState(JNIEnv *env, jclass cls, jlong h, jint which)
{
    (void)env; (void)cls;
    return (jint)mr_debug_state((void *)(intptr_t)h, (int)which);
}

extern int mr_station_call(void *h, int idx, char *buf, int cap);

JNIEXPORT jstring JNICALL
Java_com_bh4gzk_morserunner_MorseCore_stationCall(JNIEnv *env, jclass cls, jlong h, jint idx)
{
    char buf[64];
    (void)cls;
    if (mr_station_call((void *)(intptr_t)h, (int)idx, buf, (int)sizeof(buf)) < 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

extern int mr_station_exch(void *h, int idx, int which, char *buf, int cap);

JNIEXPORT jstring JNICALL
Java_com_bh4gzk_morserunner_MorseCore_stationExch(JNIEnv *env, jclass cls, jlong h, jint idx, jint which)
{
    char buf[64];
    (void)cls;
    if (mr_station_exch((void *)(intptr_t)h, (int)idx, (int)which, buf, (int)sizeof(buf)) < 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

extern void mr_set_monitor_level(void *h, float value);

JNIEXPORT void JNICALL
Java_com_bh4gzk_morserunner_MorseCore_setMonitorLevel(JNIEnv *env, jclass cls, jlong h, jfloat value)
{
    (void)env; (void)cls;
    mr_set_monitor_level((void *)(intptr_t)h, (float)value);
}

/* Three Java strings -> the form's three edit boxes. The C side tolerates nulls. */
extern void mr_set_entry(void *h, const char *call, const char *exch1, const char *exch2);

JNIEXPORT void JNICALL
Java_com_bh4gzk_morserunner_MorseCore_setEntry(JNIEnv *env, jclass cls, jlong h,
                                               jstring call, jstring exch1, jstring exch2)
{
    const char *c = NULL, *e1 = NULL, *e2 = NULL;
    (void)cls;
    if (call != NULL) c = (*env)->GetStringUTFChars(env, call, NULL);
    if (exch1 != NULL) e1 = (*env)->GetStringUTFChars(env, exch1, NULL);
    if (exch2 != NULL) e2 = (*env)->GetStringUTFChars(env, exch2, NULL);
    mr_set_entry((void *)(intptr_t)h, c, e1, e2);
    if (c != NULL) (*env)->ReleaseStringUTFChars(env, call, c);
    if (e1 != NULL) (*env)->ReleaseStringUTFChars(env, exch1, e1);
    if (e2 != NULL) (*env)->ReleaseStringUTFChars(env, exch2, e2);
}

extern int mr_save_qso(void *h);

JNIEXPORT jint JNICALL
Java_com_bh4gzk_morserunner_MorseCore_saveQso(JNIEnv *env, jclass cls, jlong h)
{
    (void)env; (void)cls;
    return (jint)mr_save_qso((void *)(intptr_t)h);
}

/* The last Pascal exception an entry point caught. The core never lets one cross the boundary
   (the RTL would end the process instead), so this is how a failure becomes a message. */
extern int mr_last_error(char *buf, int cap);

JNIEXPORT jstring JNICALL
Java_com_bh4gzk_morserunner_MorseCore_lastError(JNIEnv *env, jclass cls)
{
    char buf[512];
    (void)cls;
    if (mr_last_error(buf, (int)sizeof(buf)) < 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}
