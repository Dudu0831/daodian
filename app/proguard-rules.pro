# openai-java 的模型类全靠 Jackson 反射反序列化，不能裁。
# 这些 keep 规则会明显限制 R8 的收缩空间 —— 但这是接这个 SDK 的真实代价，
# 用更激进的规则量出来的数字是假的（运行时会崩）。
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,RuntimeVisible*Annotations

-keep class com.openai.** { *; }
-dontwarn com.openai.**

-keep class com.fasterxml.jackson.** { *; }
-keep @com.fasterxml.jackson.annotation.JsonCreator class * { *; }
-dontwarn com.fasterxml.jackson.**

-keep class kotlin.reflect.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.reflect.**

-dontwarn com.github.victools.**
-dontwarn io.swagger.**
-dontwarn org.slf4j.**
-dontwarn java.beans.**
-dontwarn javax.xml.**

# sherpa-onnx：JNI 按名字读 OnlineRecognizerConfig 这些 Kotlin 类的字段、按名字回调构造结果，
# 改名就在 native 里崩。AAR 自带的 proguard.txt 是空的，只能自己 keep
-keep class com.k2fsa.sherpa.onnx.** { *; }
