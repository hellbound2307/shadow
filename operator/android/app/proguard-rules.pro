# SHADOW Operator — minimal R8 rules (no more heavy deps)

-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Gson reflection on RpcResponse
-keep class com.shadow.operator.data.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Keep all app classes (small codebase, not worth the risk)
-keep class com.shadow.operator.** { *; }
