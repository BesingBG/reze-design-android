# 当前 release 未开启混淆（isMinifyEnabled = false）。
# 后续若开启，需要保留 JS 接口类，否则 addJavascriptInterface 会失效。
#
# -keepclassmembers class com.rezedesign.android.MainActivity$ShellBridge {
#     public *;
# }