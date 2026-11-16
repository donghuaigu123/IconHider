#!/bin/bash
set -e
BT=/root/android-sdk/android-14
AJ=/root/android-sdk/android-34/android.jar
cd /workspace/IconHider
rm -rf build && mkdir -p build/gen build/classes build/dex

echo "== 1. aapt2 编译资源 =="
$BT/aapt2 compile --dir res -o build/res.zip

echo "== 2. aapt2 链接生成 base.apk 与 R.java =="
$BT/aapt2 link -o build/base.apk -I $AJ --manifest AndroidManifest.xml \
  -R build/res.zip --java build/gen \
  --min-sdk-version 21 --target-sdk-version 34 --auto-add-overlay

echo "== 3. javac 编译 Java 源码 =="
javac -source 8 -target 8 -nowarn -classpath $AJ -d build/classes \
  build/gen/com/workbuddy/iconhider/R.java \
  src/com/workbuddy/iconhider/MainActivity.java

echo "== 4. d8 转 dex =="
$BT/d8 --release --lib $AJ --min-api 21 --output build/dex \
  $(find build/classes -name '*.class')

echo "== 5. 打包 classes.dex 进 APK =="
cd build && zip -j -q base.apk dex/classes.dex && cd ..

echo "== 6. zipalign 对齐 =="
$BT/zipalign -f 4 build/base.apk build/aligned.apk

echo "== 7. 生成签名密钥并签名 =="
if [ ! -f keystore.jks ]; then
  keytool -genkeypair -keystore keystore.jks -alias iconhider \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass iconhider2026 -keypass iconhider2026 \
    -dname "CN=IconHider, OU=Dev, O=WorkBuddy, C=CN"
fi
$BT/apksigner sign --ks keystore.jks --ks-key-alias iconhider \
  --ks-pass pass:iconhider2026 --out build/IconHider-signed.apk build/aligned.apk

$BT/apksigner verify build/IconHider-signed.apk && echo "== 验签通过 =="
cp build/IconHider-signed.apk /workspace/IconHider/apk/图标隐藏-v2.0.apk
echo "== 完成: /workspace/IconHider/apk/图标隐藏-v2.0.apk =="
