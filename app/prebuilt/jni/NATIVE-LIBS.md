# Native libs provenance
The 12 .so files in arm64-v8a/ are the complete, dependency-closed mpv
runtime set extracted from the official Aniyomi app APK
(aniyomi-arm64-v8a-v0.18.2.1.apk, github.com/aniyomiorg/aniyomi releases):
libmpv, libplayer (JNI bridge for aniyomi-mpv-lib 1.18.n, whose classes.jar
is mpv-classes.jar), FFmpeg's libavcodec/libavdevice/libavfilter/
libavformat/libavutil/libswresample/libswscale/libpostproc, libxml2 and
libc++_shared. The aniyomi-mpv-lib release AAR alone is NOT self-contained
(its libmpv.so links all of the above) — that's why the set comes from the
shipping app. All are open-source (mpv/FFmpeg, LGPL/GPL family); our app
links them unmodified.
