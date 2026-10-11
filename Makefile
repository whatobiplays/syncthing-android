# Validation entry points used by gpt-repo-local and local development.
# Keep the build target aligned with the Gradle workflow in .github/workflows/common-build.yaml.

ifeq ($(OS),Windows_NT)
ANDROID_SDK_DEFAULT := $(LOCALAPPDATA)/Android/Sdk
else ifeq ($(shell uname -s),Darwin)
ANDROID_SDK_DEFAULT := $(HOME)/Library/Android/sdk
else
ANDROID_SDK_DEFAULT := $(HOME)/Android/Sdk
endif

ANDROID_HOME ?= $(ANDROID_SDK_DEFAULT)
ANDROID_SDK_ROOT ?= $(ANDROID_HOME)
export ANDROID_HOME ANDROID_SDK_ROOT

GRADLEW := ./gradlew --no-daemon

.PHONY: all test lint build

all: test build

test:
	python3 -m unittest scripts.test_superuser_architecture
	python3 scripts/check_superuser_architecture.py
	$(GRADLEW) test

lint:
	$(GRADLEW) lintDebug

build:
	$(GRADLEW) buildNative lintDebug assembleDebug
