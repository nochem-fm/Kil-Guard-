#!/usr/bin/env bash
# aapt2 -> ecj (with Xposed API stubs, compile-only) -> d8 -> zip -> zipalign -> sign
set -e
ANDROID_JAR=${ANDROID_JAR:-$HOME/android.jar}
ECJ=${ECJ:-$HOME/ecj.jar}
SIGNER=${SIGNER:-$HOME/uber-apk-signer.jar}
rm -rf build out && mkdir -p build/stubs build/classes build/dex out
aapt2 compile --dir res -o build/res.zip
aapt2 link -o build/base.apk -I "$ANDROID_JAR" --manifest AndroidManifest.xml build/res.zip -A assets \
  --min-sdk-version 24 --target-sdk-version 33
java -jar "$ECJ" -encoding UTF-8 -source 1.8 -target 1.8 -proc:none -nowarn -cp "$ANDROID_JAR" -d build/stubs $(find stubs -name '*.java')
java -jar "$ECJ" -encoding UTF-8 -source 1.8 -target 1.8 -proc:none -nowarn -cp "$ANDROID_JAR:build/stubs" -d build/classes $(find src -name '*.java')
d8 --release --min-api 24 --lib "$ANDROID_JAR" --classpath build/stubs --output build/dex $(find build/classes -name '*.class')
( cd build/dex && zip -j ../base.apk classes.dex )
zipalign -f -p 4 build/base.apk build/aligned.apk
java -jar "$SIGNER" -a build/aligned.apk --allowResign -o out
ls -l out
