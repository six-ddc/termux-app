LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
LOCAL_ADDITIONAL_DEPENDENCIES := $(wildcard $(LOCAL_PATH)/bootstrap-*.zip)
include $(BUILD_SHARED_LIBRARY)
