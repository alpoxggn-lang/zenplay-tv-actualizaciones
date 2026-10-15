# Keep rules that :player-core imposes on anything consuming it. The engine's other keep rules still
# live in each app's proguard-rules.pro.

# Media3's FFmpeg audio renderer binds these methods from libowntvffmpeg.so with RegisterNatives.
# R8 must therefore preserve both the native method names and the Java callback invoked by JNI.
-keep class tv.own.owntv.player.ffmpeg.FfmpegLibrary {
    native <methods>;
}
-keep class tv.own.owntv.player.ffmpeg.FfmpegAudioDecoder {
    native <methods>;
    private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}
