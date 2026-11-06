// 语音模型放掉之后，把原生堆里已经释放、但分配器还攥着的空页马上还给系统。
//
// SenseVoice 跑起来 500MB 上下，放掉之后 Native Heap 里还留着约 100MB 空页（dumpsys meminfo 的 free 那一列）。
// 荣耀几分钟后自己收回去，别的手机不一定 —— 在那之前进程在后台顶着 300MB，容易被当成内存大户杀掉。
// Java / Kotlin 没有这个接口，只能从 C 调 bionic 的 mallopt。见 VOICE_PLAN.md §2.3。
#include <jni.h>
#include <malloc.h>
#include <android/api-level.h>

#ifndef M_PURGE
#define M_PURGE (-101)
#endif
#ifndef M_PURGE_ALL
#define M_PURGE_ALL (-104)
#endif

JNIEXPORT jboolean JNICALL
Java_com_abc_daodian_assistant_voice_NativeHeap_purgeNative(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    // Android 14 起有 M_PURGE_ALL，连各线程手里的缓存一起还；13 只有 M_PURGE（主缓存）
    int option = android_get_device_api_level() >= 34 ? M_PURGE_ALL : M_PURGE;
    return mallopt(option, 0) == 1 ? JNI_TRUE : JNI_FALSE;
}
