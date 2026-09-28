# R8 rules. Health Connect, WorkManager, OkHttp and kotlinx.serialization ship their
# own consumer rules; keep our JSON DTOs intact as an extra safety net.
-keep class com.fadi.healthsync.**Dto { *; }
-keep class com.fadi.healthsync.IngestPayload { *; }
-dontwarn org.slf4j.**
