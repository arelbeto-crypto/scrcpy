#!/usr/bin/env bash
set -eu
root=$(cd "$(dirname "$0")/../../.." && pwd)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
java -m jdk.compiler/com.sun.tools.javac.Main -d "$out" "$root/release/poco/tests/support/android/graphics/Rect.java" \
  "$root/release/poco/tests/support/android/graphics/Bitmap.java" \
  "$root/release/poco/tests/support/com/genymobile/scrcpy/model/Codec.java" \
  "$root/release/poco/tests/support/com/genymobile/scrcpy/util/Ln.java" \
  "$root/release/poco/v84/RemoteVisionState.java" \
  "$root/release/poco/v84/SmartZoomController.java" \
  "$root/release/poco/v84/VisionBridge.java" \
  "$root/release/poco/v84/VisionFacePacket.java" \
  "$root/release/poco/redmi-vision/app/src/main/java/com/poco/visioncoprocessor/FaceSelection.java" \
  "$root/release/poco/redmi-vision/app/src/main/java/com/poco/visioncoprocessor/MediaPipeFrame.java" \
  "$root/release/poco/common/java/com/poco/vision/FaceTopology.java" \
  "$root/release/poco/tests/MediaPipeFrameTest.java" \
  "$root/release/poco/tests/FaceSelectionTest.java" \
  "$root/release/poco/tests/VisionStateTest.java" \
  "$root/release/poco/tests/SmartZoomControllerTest.java" \
  "$root/release/poco/tests/VisionBridgeTest.java" \
  "$root/release/poco/tests/VisionPacketTest.java"
java -cp "$out" com.genymobile.scrcpy.video.VisionStateTest
java -cp "$out" com.genymobile.scrcpy.video.SmartZoomControllerTest
java -cp "$out" com.genymobile.scrcpy.video.VisionPacketTest
java -cp "$out" com.poco.visioncoprocessor.FaceSelectionTest
java -cp "$out" com.poco.visioncoprocessor.MediaPipeFrameTest
java -cp "$out" com.genymobile.scrcpy.video.VisionBridgeTest
