# Preserve the WorkManager worker's class name and required constructor.
# WorkManager persists worker class names across process restarts and app updates.
-keep class com.gunakarna.jazzrecovery.RecoveryHeartbeatWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep line information for private crash symbolication; retain this mapping file securely.
-keepattributes SourceFile,LineNumberTable,Signature,RuntimeVisibleAnnotations,AnnotationDefault
-renamesourcefileattribute SourceFile
