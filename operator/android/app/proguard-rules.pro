# SHADOW Operator — minimal R8 rules (no more heavy deps)

-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Gson reflection on RpcResponse
-keep class com.shadow.operator.data.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Keep all app classes (small codebase, not worth the risk)
-keep class com.shadow.operator.** { *; }

# BouncyCastle Ed25519 (operator RPC signing). R8 otherwise strips/refactors the
# low-level crypto internals this path reaches through.
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.util.** { *; }
-keep class org.bouncycastle.math.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn java.lang.management.**
