.PHONY: apk debug release install clean

APK_DEBUG := app/build/outputs/apk/debug/app-debug.apk
APK_RELEASE := app/build/outputs/apk/release/app-release-unsigned.apk
NAME ?= kanji-kumitate
DIST_DIR := dist
DIST_APK := $(DIST_DIR)/$(NAME).apk

apk: debug
	mkdir -p $(DIST_DIR)
	cp $(APK_DEBUG) $(DIST_APK)
	@echo "Named APK: $(DIST_APK)"

debug:
	./gradlew assembleDebug
	@echo "APK: $(APK_DEBUG)"

release:
	./gradlew assembleRelease
	@echo "APK: $(APK_RELEASE)"

install: debug
	adb install -r $(APK_DEBUG)

clean:
	./gradlew clean
