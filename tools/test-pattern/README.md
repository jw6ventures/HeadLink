Lab and test video source (never packaged): an H.264 encoder that writes only the two
simplest macroblock kinds — every macroblock of an IDR is I_PCM (raw samples), and a P
picture codes changed macroblocks as I_PCM and skips the rest — plus a YUV 4:2:0 canvas
with rectangles and digits. The stream is large but trivially correct, so session tests and
the lab harness exercise real parameter sets, IDR recovery, and receiver decoding without a
platform codec. The app uses MediaCodec instead.
