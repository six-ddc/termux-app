LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c ghostty_bridge.c
LOCAL_LDLIBS:= -ldl
include $(BUILD_SHARED_LIBRARY)
