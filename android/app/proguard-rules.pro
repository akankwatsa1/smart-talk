# SmartTalk keeps R8 disabled for the first releases. These rules are here so
# that enabling minification later is a one-line change.
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
