# Release 版目前不開混淆（isMinifyEnabled = false）。
# 若要開，Anthropic SDK 依賴 Jackson 反射，需保留 com.anthropic.** 與 com.fasterxml.jackson.**。
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn **
