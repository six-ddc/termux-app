#include <dlfcn.h>
#include <android/keycodes.h>
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define TERMUX_UNUSED(x) x __attribute__((__unused__))

typedef void* GhosttyTerminal;
typedef int GhosttyResult;
typedef uint16_t GhosttyMode;
typedef void* GhosttyRenderState;
typedef void* GhosttyRenderStateRowIterator;
typedef void* GhosttyRenderStateRowCells;
typedef void* GhosttyKittyGraphics;
typedef const void* GhosttyKittyGraphicsImage;
typedef void* GhosttyKittyGraphicsPlacementIterator;
typedef void* AImageDecoder;
typedef const void* AImageDecoderHeaderInfo;
typedef void* GhosttyKeyEncoder;
typedef void* GhosttyKeyEvent;
typedef void* GhosttyMouseEncoder;
typedef void* GhosttyMouseEvent;
typedef uint16_t GhosttyMods;
typedef uint64_t GhosttyCell;

#define GHOSTTY_OUT_OF_SPACE (-3)
#define GHOSTTY_NO_VALUE (-4)

#define GHOSTTY_POINT_TAG_SCREEN 2
#define GHOSTTY_FORMATTER_FORMAT_PLAIN 0

#define TERMUX_KEYMOD_ALT ((jint) 0x80000000u)
#define TERMUX_KEYMOD_CTRL ((jint) 0x40000000u)
#define TERMUX_KEYMOD_SHIFT ((jint) 0x20000000u)
#define TERMUX_KEYMOD_NUM_LOCK ((jint) 0x10000000u)

#define GHOSTTY_MODS_SHIFT (1 << 0)
#define GHOSTTY_MODS_CTRL (1 << 1)
#define GHOSTTY_MODS_ALT (1 << 2)
#define GHOSTTY_MODS_NUM_LOCK (1 << 5)

#define GHOSTTY_KEY_ACTION_PRESS 1
#define GHOSTTY_FOCUS_GAINED 0
#define GHOSTTY_FOCUS_LOST 1
#define GHOSTTY_MODE_BRACKETED_PASTE 2004

#define GHOSTTY_MOUSE_ACTION_PRESS 0
#define GHOSTTY_MOUSE_ACTION_RELEASE 1
#define GHOSTTY_MOUSE_ACTION_MOTION 2
#define GHOSTTY_MOUSE_BUTTON_LEFT 1
#define GHOSTTY_MOUSE_BUTTON_FOUR 4
#define GHOSTTY_MOUSE_BUTTON_FIVE 5
#define GHOSTTY_MOUSE_ENCODER_OPT_SIZE 2

#define GHOSTTY_CELL_DATA_WIDE 3
#define GHOSTTY_CELL_DATA_PROTECTED 8
#define GHOSTTY_CELL_WIDE_NARROW 0
#define GHOSTTY_CELL_WIDE_WIDE 1
#define GHOSTTY_CELL_WIDE_SPACER_TAIL 2
#define GHOSTTY_CELL_WIDE_SPACER_HEAD 3
#define GHOSTTY_RENDER_CELL_SELECTED 7
#define TERMUX_RENDER_CELL_COLOR_DEFAULT ((jint) 0x80000000u)
#define GHOSTTY_TERMINAL_OPT_USERDATA 0
#define GHOSTTY_TERMINAL_OPT_WRITE_PTY 1
#define GHOSTTY_TERMINAL_OPT_BELL 2
#define GHOSTTY_TERMINAL_OPT_ENQUIRY 3
#define GHOSTTY_TERMINAL_OPT_XTVERSION 4
#define GHOSTTY_TERMINAL_OPT_TITLE_CHANGED 5
#define GHOSTTY_TERMINAL_OPT_SIZE 6
#define GHOSTTY_TERMINAL_OPT_COLOR_SCHEME 7
#define GHOSTTY_TERMINAL_OPT_DEVICE_ATTRIBUTES 8
#define GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND 11
#define GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND 12
#define GHOSTTY_TERMINAL_OPT_COLOR_CURSOR 13
#define GHOSTTY_TERMINAL_OPT_COLOR_PALETTE 14
#define GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS 30
#define GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT 15
#define GHOSTTY_KITTY_GRAPHICS_DATA_PLACEMENT_ITERATOR 1
#define GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IMAGE_ID 1
#define GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_PLACEMENT_ID 2
#define GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IS_VIRTUAL 3
#define GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_X_OFFSET 4
#define GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Y_OFFSET 5
#define GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Z 12
#define GHOSTTY_KITTY_IMAGE_DATA_WIDTH 3
#define GHOSTTY_KITTY_IMAGE_DATA_HEIGHT 4
#define GHOSTTY_KITTY_IMAGE_DATA_FORMAT 5
#define GHOSTTY_KITTY_IMAGE_DATA_COMPRESSION 6
#define GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR 7
#define GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN 8
#define GHOSTTY_SYS_OPT_DECODE_PNG 1
#define GHOSTTY_DA_CONFORMANCE_VT420 64
#define GHOSTTY_DA_DEVICE_TYPE_VT420 41
#define GHOSTTY_DA_FEATURE_COLUMNS_132 1
#define GHOSTTY_DA_FEATURE_PRINTER 2
#define GHOSTTY_DA_FEATURE_SELECTIVE_ERASE 6
#define GHOSTTY_DA_FEATURE_NATIONAL_REPLACEMENT 9
#define GHOSTTY_DA_FEATURE_TECHNICAL_CHARACTERS 15
#define GHOSTTY_DA_FEATURE_WINDOWING 18
#define GHOSTTY_DA_FEATURE_HORIZONTAL_SCROLLING 21
#define GHOSTTY_DA_FEATURE_ANSI_COLOR 22
#define ANDROID_IMAGE_DECODER_SUCCESS 0
#define ANDROID_BITMAP_FORMAT_RGBA_8888 1

#define TERMUX_RENDER_CELL_STRIDE 9
#define TERMUX_CHARACTER_ATTRIBUTE_PROTECTED (1 << 7)

typedef struct {
    size_t size;
    uint32_t screen_width;
    uint32_t screen_height;
    uint32_t cell_width;
    uint32_t cell_height;
    uint32_t padding_top;
    uint32_t padding_bottom;
    uint32_t padding_right;
    uint32_t padding_left;
} GhosttyMouseEncoderSize;

typedef struct {
    float x;
    float y;
} GhosttyMousePosition;

typedef enum {
    GHOSTTY_KEY_UNIDENTIFIED = 0,
    GHOSTTY_KEY_BACKQUOTE = 1,
    GHOSTTY_KEY_BACKSLASH = 2,
    GHOSTTY_KEY_BRACKET_LEFT = 3,
    GHOSTTY_KEY_BRACKET_RIGHT = 4,
    GHOSTTY_KEY_COMMA = 5,
    GHOSTTY_KEY_DIGIT_0 = 6,
    GHOSTTY_KEY_DIGIT_1 = 7,
    GHOSTTY_KEY_DIGIT_2 = 8,
    GHOSTTY_KEY_DIGIT_3 = 9,
    GHOSTTY_KEY_DIGIT_4 = 10,
    GHOSTTY_KEY_DIGIT_5 = 11,
    GHOSTTY_KEY_DIGIT_6 = 12,
    GHOSTTY_KEY_DIGIT_7 = 13,
    GHOSTTY_KEY_DIGIT_8 = 14,
    GHOSTTY_KEY_DIGIT_9 = 15,
    GHOSTTY_KEY_EQUAL = 16,
    GHOSTTY_KEY_A = 20,
    GHOSTTY_KEY_B = 21,
    GHOSTTY_KEY_C = 22,
    GHOSTTY_KEY_D = 23,
    GHOSTTY_KEY_E = 24,
    GHOSTTY_KEY_F = 25,
    GHOSTTY_KEY_G = 26,
    GHOSTTY_KEY_H = 27,
    GHOSTTY_KEY_I = 28,
    GHOSTTY_KEY_J = 29,
    GHOSTTY_KEY_K = 30,
    GHOSTTY_KEY_L = 31,
    GHOSTTY_KEY_M = 32,
    GHOSTTY_KEY_N = 33,
    GHOSTTY_KEY_O = 34,
    GHOSTTY_KEY_P = 35,
    GHOSTTY_KEY_Q = 36,
    GHOSTTY_KEY_R = 37,
    GHOSTTY_KEY_S = 38,
    GHOSTTY_KEY_T = 39,
    GHOSTTY_KEY_U = 40,
    GHOSTTY_KEY_V = 41,
    GHOSTTY_KEY_W = 42,
    GHOSTTY_KEY_X = 43,
    GHOSTTY_KEY_Y = 44,
    GHOSTTY_KEY_Z = 45,
    GHOSTTY_KEY_MINUS = 46,
    GHOSTTY_KEY_PERIOD = 47,
    GHOSTTY_KEY_QUOTE = 48,
    GHOSTTY_KEY_SEMICOLON = 49,
    GHOSTTY_KEY_SLASH = 50,
    GHOSTTY_KEY_BACKSPACE = 53,
    GHOSTTY_KEY_ENTER = 58,
    GHOSTTY_KEY_SPACE = 63,
    GHOSTTY_KEY_TAB = 64,
    GHOSTTY_KEY_DELETE = 68,
    GHOSTTY_KEY_END = 69,
    GHOSTTY_KEY_HOME = 71,
    GHOSTTY_KEY_INSERT = 72,
    GHOSTTY_KEY_PAGE_DOWN = 73,
    GHOSTTY_KEY_PAGE_UP = 74,
    GHOSTTY_KEY_ARROW_DOWN = 75,
    GHOSTTY_KEY_ARROW_LEFT = 76,
    GHOSTTY_KEY_ARROW_RIGHT = 77,
    GHOSTTY_KEY_ARROW_UP = 78,
    GHOSTTY_KEY_NUM_LOCK = 79,
    GHOSTTY_KEY_NUMPAD_0 = 80,
    GHOSTTY_KEY_NUMPAD_1 = 81,
    GHOSTTY_KEY_NUMPAD_2 = 82,
    GHOSTTY_KEY_NUMPAD_3 = 83,
    GHOSTTY_KEY_NUMPAD_4 = 84,
    GHOSTTY_KEY_NUMPAD_5 = 85,
    GHOSTTY_KEY_NUMPAD_6 = 86,
    GHOSTTY_KEY_NUMPAD_7 = 87,
    GHOSTTY_KEY_NUMPAD_8 = 88,
    GHOSTTY_KEY_NUMPAD_9 = 89,
    GHOSTTY_KEY_NUMPAD_ADD = 90,
    GHOSTTY_KEY_NUMPAD_COMMA = 94,
    GHOSTTY_KEY_NUMPAD_DECIMAL = 95,
    GHOSTTY_KEY_NUMPAD_DIVIDE = 96,
    GHOSTTY_KEY_NUMPAD_ENTER = 97,
    GHOSTTY_KEY_NUMPAD_EQUAL = 98,
    GHOSTTY_KEY_NUMPAD_MULTIPLY = 104,
    GHOSTTY_KEY_NUMPAD_SUBTRACT = 107,
    GHOSTTY_KEY_ESCAPE = 120,
    GHOSTTY_KEY_F1 = 121,
    GHOSTTY_KEY_F2 = 122,
    GHOSTTY_KEY_F3 = 123,
    GHOSTTY_KEY_F4 = 124,
    GHOSTTY_KEY_F5 = 125,
    GHOSTTY_KEY_F6 = 126,
    GHOSTTY_KEY_F7 = 127,
    GHOSTTY_KEY_F8 = 128,
    GHOSTTY_KEY_F9 = 129,
    GHOSTTY_KEY_F10 = 130,
    GHOSTTY_KEY_F11 = 131,
    GHOSTTY_KEY_F12 = 132,
    GHOSTTY_KEY_PRINT_SCREEN = 148,
    GHOSTTY_KEY_PAUSE = 150,
} GhosttyKey;

typedef struct {
    uint16_t cols;
    uint16_t rows;
    size_t max_scrollback;
} GhosttyTerminalOptions;

typedef union {
    intptr_t delta;
    uint64_t _padding[2];
} GhosttyTerminalScrollViewportValue;

typedef struct {
    int tag;
    GhosttyTerminalScrollViewportValue value;
} GhosttyTerminalScrollViewport;

typedef struct {
    uint64_t total;
    uint64_t offset;
    uint64_t len;
} GhosttyTerminalScrollbar;

typedef struct {
    const uint8_t* ptr;
    size_t len;
} GhosttyString;

typedef struct {
    uint16_t rows;
    uint16_t columns;
    uint32_t cell_width;
    uint32_t cell_height;
} GhosttySizeReportSize;

typedef struct {
    uint16_t conformance_level;
    uint16_t features[64];
    size_t num_features;
} GhosttyDeviceAttributesPrimary;

typedef struct {
    uint16_t device_type;
    uint16_t firmware_version;
    uint16_t rom_cartridge;
} GhosttyDeviceAttributesSecondary;

typedef struct {
    uint32_t unit_id;
} GhosttyDeviceAttributesTertiary;

typedef struct {
    GhosttyDeviceAttributesPrimary primary;
    GhosttyDeviceAttributesSecondary secondary;
    GhosttyDeviceAttributesTertiary tertiary;
} GhosttyDeviceAttributes;

typedef struct {
    uint16_t x;
    uint32_t y;
} GhosttyPointCoordinate;

typedef union {
    GhosttyPointCoordinate coordinate;
    uint64_t _padding[2];
} GhosttyPointValue;

typedef struct {
    int tag;
    GhosttyPointValue value;
} GhosttyPoint;

typedef struct {
    size_t size;
    void* node;
    uint16_t x;
    uint16_t y;
} GhosttyGridRef;

typedef struct {
    size_t size;
    GhosttyGridRef start;
    GhosttyGridRef end;
    bool rectangle;
} GhosttySelection;

typedef struct {
    size_t size;
    int emit;
    bool unwrap;
    bool trim;
    const GhosttySelection* selection;
} GhosttyTerminalSelectionFormatOptions;

typedef struct {
    uint32_t width;
    uint32_t height;
    uint8_t* data;
    size_t data_len;
} GhosttySysImage;

typedef struct {
    size_t size;
    uint32_t pixel_width;
    uint32_t pixel_height;
    uint32_t grid_cols;
    uint32_t grid_rows;
    int32_t viewport_col;
    int32_t viewport_row;
    bool viewport_visible;
    uint32_t source_x;
    uint32_t source_y;
    uint32_t source_width;
    uint32_t source_height;
} GhosttyKittyGraphicsPlacementRenderInfo;

typedef struct {
    size_t size;
    GhosttyGridRef ref;
    const uint32_t* boundary_codepoints;
    size_t boundary_codepoints_len;
} GhosttyTerminalSelectWordOptions;

typedef struct {
    uint8_t r;
    uint8_t g;
    uint8_t b;
} GhosttyColorRgb;

typedef enum {
    GHOSTTY_STYLE_COLOR_NONE = 0,
    GHOSTTY_STYLE_COLOR_PALETTE = 1,
    GHOSTTY_STYLE_COLOR_RGB = 2,
} GhosttyStyleColorTag;

typedef union {
    uint8_t palette;
    GhosttyColorRgb rgb;
    uint64_t _padding;
} GhosttyStyleColorValue;

typedef struct {
    GhosttyStyleColorTag tag;
    GhosttyStyleColorValue value;
} GhosttyStyleColor;

typedef struct {
    size_t size;
    GhosttyStyleColor fg_color;
    GhosttyStyleColor bg_color;
    GhosttyStyleColor underline_color;
    bool bold;
    bool italic;
    bool faint;
    bool blink;
    bool inverse;
    bool invisible;
    bool strikethrough;
    bool overline;
    int underline;
} GhosttyStyle;

typedef struct {
    size_t size;
    GhosttyColorRgb background;
    GhosttyColorRgb foreground;
    GhosttyColorRgb cursor;
    bool cursor_has_value;
    GhosttyColorRgb palette[256];
} GhosttyRenderStateColors;

typedef struct {
    GhosttyTerminal terminal;
    GhosttyRenderState render_state;
    uint16_t columns;
    uint16_t rows;
    uint32_t cell_width_pixels;
    uint32_t cell_height_pixels;
    uint8_t* pending_pty_write;
    size_t pending_pty_write_len;
    size_t pending_pty_write_cap;
    bool pending_pty_write_oom;
    uint32_t pending_bell_count;
    bool pending_title_changed;
} GhosttyBridgeContext;

typedef GhosttyResult (*ghostty_terminal_new_fn)(const void*, GhosttyTerminal*, GhosttyTerminalOptions);
typedef void (*ghostty_terminal_free_fn)(GhosttyTerminal);
typedef void (*ghostty_terminal_reset_fn)(GhosttyTerminal);
typedef GhosttyResult (*ghostty_terminal_resize_fn)(GhosttyTerminal, uint16_t, uint16_t, uint32_t, uint32_t);
typedef void (*ghostty_terminal_vt_write_fn)(GhosttyTerminal, const uint8_t*, size_t);
typedef GhosttyResult (*ghostty_terminal_set_fn)(GhosttyTerminal, int, const void*);
typedef void (*ghostty_terminal_scroll_viewport_fn)(GhosttyTerminal, GhosttyTerminalScrollViewport);
typedef GhosttyResult (*ghostty_terminal_get_fn)(GhosttyTerminal, int, void*);
typedef GhosttyResult (*ghostty_terminal_mode_get_fn)(GhosttyTerminal, GhosttyMode, bool*);
typedef GhosttyResult (*ghostty_terminal_grid_ref_fn)(GhosttyTerminal, GhosttyPoint, GhosttyGridRef*);
typedef GhosttyResult (*ghostty_terminal_select_word_fn)(GhosttyTerminal, const GhosttyTerminalSelectWordOptions*, GhosttySelection*);
typedef GhosttyResult (*ghostty_terminal_point_from_grid_ref_fn)(GhosttyTerminal, const GhosttyGridRef*, int, GhosttyPointCoordinate*);
typedef GhosttyResult (*ghostty_terminal_selection_format_buf_fn)(GhosttyTerminal, GhosttyTerminalSelectionFormatOptions, uint8_t*, size_t, size_t*);
typedef GhosttyResult (*ghostty_grid_ref_hyperlink_uri_fn)(const GhosttyGridRef*, uint8_t*, size_t, size_t*);
typedef GhosttyResult (*ghostty_render_state_new_fn)(const void*, GhosttyRenderState*);
typedef void (*ghostty_render_state_free_fn)(GhosttyRenderState);
typedef GhosttyResult (*ghostty_render_state_update_fn)(GhosttyRenderState, GhosttyTerminal);
typedef GhosttyResult (*ghostty_render_state_get_fn)(GhosttyRenderState, int, void*);
typedef GhosttyResult (*ghostty_render_state_set_fn)(GhosttyRenderState, int, const void*);
typedef GhosttyResult (*ghostty_render_state_colors_get_fn)(GhosttyRenderState, GhosttyRenderStateColors*);
typedef GhosttyResult (*ghostty_render_state_row_iterator_new_fn)(const void*, GhosttyRenderStateRowIterator*);
typedef void (*ghostty_render_state_row_iterator_free_fn)(GhosttyRenderStateRowIterator);
typedef bool (*ghostty_render_state_row_iterator_next_fn)(GhosttyRenderStateRowIterator);
typedef GhosttyResult (*ghostty_render_state_row_get_fn)(GhosttyRenderStateRowIterator, int, void*);
typedef GhosttyResult (*ghostty_render_state_row_set_fn)(GhosttyRenderStateRowIterator, int, const void*);
typedef GhosttyResult (*ghostty_render_state_row_cells_new_fn)(const void*, GhosttyRenderStateRowCells*);
typedef void (*ghostty_render_state_row_cells_free_fn)(GhosttyRenderStateRowCells);
typedef bool (*ghostty_render_state_row_cells_next_fn)(GhosttyRenderStateRowCells);
typedef GhosttyResult (*ghostty_render_state_row_cells_get_fn)(GhosttyRenderStateRowCells, int, void*);
typedef GhosttyResult (*ghostty_kitty_graphics_get_fn)(GhosttyKittyGraphics, int, void*);
typedef GhosttyKittyGraphicsImage (*ghostty_kitty_graphics_image_fn)(GhosttyKittyGraphics, uint32_t);
typedef GhosttyResult (*ghostty_kitty_graphics_image_get_fn)(GhosttyKittyGraphicsImage, int, void*);
typedef GhosttyResult (*ghostty_kitty_graphics_placement_iterator_new_fn)(const void*, GhosttyKittyGraphicsPlacementIterator*);
typedef void (*ghostty_kitty_graphics_placement_iterator_free_fn)(GhosttyKittyGraphicsPlacementIterator);
typedef bool (*ghostty_kitty_graphics_placement_next_fn)(GhosttyKittyGraphicsPlacementIterator);
typedef GhosttyResult (*ghostty_kitty_graphics_placement_get_fn)(GhosttyKittyGraphicsPlacementIterator, int, void*);
typedef GhosttyResult (*ghostty_kitty_graphics_placement_render_info_fn)(
        GhosttyKittyGraphicsPlacementIterator, GhosttyKittyGraphicsImage, GhosttyTerminal,
        GhosttyKittyGraphicsPlacementRenderInfo*);
typedef GhosttyResult (*ghostty_sys_set_fn)(int, const void*);
typedef uint8_t* (*ghostty_alloc_fn)(const void*, size_t);
typedef void (*ghostty_free_fn)(const void*, uint8_t*, size_t);
typedef bool (*GhosttySysDecodePngFn)(void*, const void*, const uint8_t*, size_t, GhosttySysImage*);
typedef int (*AImageDecoder_createFromBuffer_fn)(const void*, size_t, AImageDecoder**);
typedef void (*AImageDecoder_delete_fn)(AImageDecoder*);
typedef AImageDecoderHeaderInfo (*AImageDecoder_getHeaderInfo_fn)(AImageDecoder*);
typedef int32_t (*AImageDecoderHeaderInfo_getWidth_fn)(AImageDecoderHeaderInfo);
typedef int32_t (*AImageDecoderHeaderInfo_getHeight_fn)(AImageDecoderHeaderInfo);
typedef int (*AImageDecoder_setAndroidBitmapFormat_fn)(AImageDecoder*, int);
typedef int (*AImageDecoder_setUnpremultipliedRequired_fn)(AImageDecoder*, bool);
typedef int (*AImageDecoder_decodeImage_fn)(AImageDecoder*, void*, size_t, size_t);
typedef GhosttyResult (*ghostty_cell_get_fn)(GhosttyCell, int, void*);
typedef GhosttyResult (*ghostty_key_encoder_new_fn)(const void*, GhosttyKeyEncoder*);
typedef void (*ghostty_key_encoder_free_fn)(GhosttyKeyEncoder);
typedef void (*ghostty_key_encoder_setopt_from_terminal_fn)(GhosttyKeyEncoder, GhosttyTerminal);
typedef GhosttyResult (*ghostty_key_encoder_encode_fn)(GhosttyKeyEncoder, GhosttyKeyEvent, char*, size_t, size_t*);
typedef GhosttyResult (*ghostty_key_event_new_fn)(const void*, GhosttyKeyEvent*);
typedef void (*ghostty_key_event_free_fn)(GhosttyKeyEvent);
typedef void (*ghostty_key_event_set_action_fn)(GhosttyKeyEvent, int);
typedef void (*ghostty_key_event_set_key_fn)(GhosttyKeyEvent, GhosttyKey);
typedef void (*ghostty_key_event_set_mods_fn)(GhosttyKeyEvent, GhosttyMods);
typedef void (*ghostty_key_event_set_utf8_fn)(GhosttyKeyEvent, const char*, size_t);
typedef GhosttyResult (*ghostty_focus_encode_fn)(int, char*, size_t, size_t*);
typedef GhosttyResult (*ghostty_paste_encode_fn)(char*, size_t, bool, char*, size_t, size_t*);
typedef GhosttyResult (*ghostty_mouse_encoder_new_fn)(const void*, GhosttyMouseEncoder*);
typedef void (*ghostty_mouse_encoder_free_fn)(GhosttyMouseEncoder);
typedef void (*ghostty_mouse_encoder_setopt_fn)(GhosttyMouseEncoder, int, const void*);
typedef void (*ghostty_mouse_encoder_setopt_from_terminal_fn)(GhosttyMouseEncoder, GhosttyTerminal);
typedef GhosttyResult (*ghostty_mouse_encoder_encode_fn)(GhosttyMouseEncoder, GhosttyMouseEvent, char*, size_t, size_t*);
typedef GhosttyResult (*ghostty_mouse_event_new_fn)(const void*, GhosttyMouseEvent*);
typedef void (*ghostty_mouse_event_free_fn)(GhosttyMouseEvent);
typedef void (*ghostty_mouse_event_set_action_fn)(GhosttyMouseEvent, int);
typedef void (*ghostty_mouse_event_set_button_fn)(GhosttyMouseEvent, int);
typedef void (*ghostty_mouse_event_set_position_fn)(GhosttyMouseEvent, GhosttyMousePosition);

static void* gGhosttyHandle;
static ghostty_terminal_new_fn gTerminalNew;
static ghostty_terminal_free_fn gTerminalFree;
static ghostty_terminal_reset_fn gTerminalReset;
static ghostty_terminal_resize_fn gTerminalResize;
static ghostty_terminal_vt_write_fn gTerminalVtWrite;
static ghostty_terminal_set_fn gTerminalSet;
static ghostty_terminal_scroll_viewport_fn gTerminalScrollViewport;
static ghostty_terminal_get_fn gTerminalGet;
static ghostty_terminal_mode_get_fn gTerminalModeGet;
static ghostty_terminal_grid_ref_fn gTerminalGridRef;
static ghostty_terminal_select_word_fn gTerminalSelectWord;
static ghostty_terminal_point_from_grid_ref_fn gTerminalPointFromGridRef;
static ghostty_terminal_selection_format_buf_fn gTerminalSelectionFormatBuf;
static ghostty_grid_ref_hyperlink_uri_fn gGridRefHyperlinkUri;
static ghostty_render_state_new_fn gRenderStateNew;
static ghostty_render_state_free_fn gRenderStateFree;
static ghostty_render_state_update_fn gRenderStateUpdate;
static ghostty_render_state_get_fn gRenderStateGet;
static ghostty_render_state_set_fn gRenderStateSet;
static ghostty_render_state_colors_get_fn gRenderStateColorsGet;
static ghostty_render_state_row_iterator_new_fn gRenderStateRowIteratorNew;
static ghostty_render_state_row_iterator_free_fn gRenderStateRowIteratorFree;
static ghostty_render_state_row_iterator_next_fn gRenderStateRowIteratorNext;
static ghostty_render_state_row_get_fn gRenderStateRowGet;
static ghostty_render_state_row_set_fn gRenderStateRowSet;
static ghostty_render_state_row_cells_new_fn gRenderStateRowCellsNew;
static ghostty_render_state_row_cells_free_fn gRenderStateRowCellsFree;
static ghostty_render_state_row_cells_next_fn gRenderStateRowCellsNext;
static ghostty_render_state_row_cells_get_fn gRenderStateRowCellsGet;
static ghostty_kitty_graphics_get_fn gKittyGraphicsGet;
static ghostty_kitty_graphics_image_fn gKittyGraphicsImage;
static ghostty_kitty_graphics_image_get_fn gKittyGraphicsImageGet;
static ghostty_kitty_graphics_placement_iterator_new_fn gKittyGraphicsPlacementIteratorNew;
static ghostty_kitty_graphics_placement_iterator_free_fn gKittyGraphicsPlacementIteratorFree;
static ghostty_kitty_graphics_placement_next_fn gKittyGraphicsPlacementNext;
static ghostty_kitty_graphics_placement_get_fn gKittyGraphicsPlacementGet;
static ghostty_kitty_graphics_placement_render_info_fn gKittyGraphicsPlacementRenderInfo;
static ghostty_sys_set_fn gGhosttySysSet;
static ghostty_alloc_fn gGhosttyAlloc;
static ghostty_free_fn gGhosttyFree;
static ghostty_cell_get_fn gCellGet;
static ghostty_key_encoder_new_fn gKeyEncoderNew;
static ghostty_key_encoder_free_fn gKeyEncoderFree;
static ghostty_key_encoder_setopt_from_terminal_fn gKeyEncoderSetoptFromTerminal;
static ghostty_key_encoder_encode_fn gKeyEncoderEncode;
static ghostty_key_event_new_fn gKeyEventNew;
static ghostty_key_event_free_fn gKeyEventFree;
static ghostty_key_event_set_action_fn gKeyEventSetAction;
static ghostty_key_event_set_key_fn gKeyEventSetKey;
static ghostty_key_event_set_mods_fn gKeyEventSetMods;
static ghostty_key_event_set_utf8_fn gKeyEventSetUtf8;
static ghostty_focus_encode_fn gFocusEncode;
static ghostty_paste_encode_fn gPasteEncode;
static ghostty_mouse_encoder_new_fn gMouseEncoderNew;
static ghostty_mouse_encoder_free_fn gMouseEncoderFree;
static ghostty_mouse_encoder_setopt_fn gMouseEncoderSetopt;
static ghostty_mouse_encoder_setopt_from_terminal_fn gMouseEncoderSetoptFromTerminal;
static ghostty_mouse_encoder_encode_fn gMouseEncoderEncode;
static ghostty_mouse_event_new_fn gMouseEventNew;
static ghostty_mouse_event_free_fn gMouseEventFree;
static ghostty_mouse_event_set_action_fn gMouseEventSetAction;
static ghostty_mouse_event_set_button_fn gMouseEventSetButton;
static ghostty_mouse_event_set_position_fn gMouseEventSetPosition;
static void* gAndroidImageDecoderHandle;
static AImageDecoder_createFromBuffer_fn gAImageDecoderCreateFromBuffer;
static AImageDecoder_delete_fn gAImageDecoderDelete;
static AImageDecoder_getHeaderInfo_fn gAImageDecoderGetHeaderInfo;
static AImageDecoderHeaderInfo_getWidth_fn gAImageDecoderHeaderInfoGetWidth;
static AImageDecoderHeaderInfo_getHeight_fn gAImageDecoderHeaderInfoGetHeight;
static AImageDecoder_setAndroidBitmapFormat_fn gAImageDecoderSetAndroidBitmapFormat;
static AImageDecoder_setUnpremultipliedRequired_fn gAImageDecoderSetUnpremultipliedRequired;
static AImageDecoder_decodeImage_fn gAImageDecoderDecodeImage;
static bool gGhosttySysCallbacksInstalled;

static int throw_runtime_exception(JNIEnv* env, const char* message) {
    jclass exClass = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, exClass, message);
    return -1;
}

static bool termux_ghostty_decode_png(void* userdata, const void* allocator,
                                      const uint8_t* data, size_t data_len, GhosttySysImage* out);

static void* load_symbol(const char* name) {
    return dlsym(gGhosttyHandle, name);
}

static void* load_android_image_decoder_symbol(const char* name) {
    return dlsym(gAndroidImageDecoderHandle, name);
}

static bool load_android_image_decoder_symbols(void) {
    if (gAndroidImageDecoderHandle != NULL) return true;

    gAndroidImageDecoderHandle = dlopen("libjnigraphics.so", RTLD_NOW | RTLD_LOCAL);
    if (gAndroidImageDecoderHandle == NULL)
        return false;

    gAImageDecoderCreateFromBuffer =
        (AImageDecoder_createFromBuffer_fn) load_android_image_decoder_symbol("AImageDecoder_createFromBuffer");
    gAImageDecoderDelete = (AImageDecoder_delete_fn) load_android_image_decoder_symbol("AImageDecoder_delete");
    gAImageDecoderGetHeaderInfo =
        (AImageDecoder_getHeaderInfo_fn) load_android_image_decoder_symbol("AImageDecoder_getHeaderInfo");
    gAImageDecoderHeaderInfoGetWidth =
        (AImageDecoderHeaderInfo_getWidth_fn) load_android_image_decoder_symbol("AImageDecoderHeaderInfo_getWidth");
    gAImageDecoderHeaderInfoGetHeight =
        (AImageDecoderHeaderInfo_getHeight_fn) load_android_image_decoder_symbol("AImageDecoderHeaderInfo_getHeight");
    gAImageDecoderSetAndroidBitmapFormat =
        (AImageDecoder_setAndroidBitmapFormat_fn) load_android_image_decoder_symbol("AImageDecoder_setAndroidBitmapFormat");
    gAImageDecoderSetUnpremultipliedRequired =
        (AImageDecoder_setUnpremultipliedRequired_fn) load_android_image_decoder_symbol("AImageDecoder_setUnpremultipliedRequired");
    gAImageDecoderDecodeImage = (AImageDecoder_decodeImage_fn) load_android_image_decoder_symbol("AImageDecoder_decodeImage");

    if (gAImageDecoderCreateFromBuffer == NULL || gAImageDecoderDelete == NULL ||
        gAImageDecoderGetHeaderInfo == NULL || gAImageDecoderHeaderInfoGetWidth == NULL ||
        gAImageDecoderHeaderInfoGetHeight == NULL || gAImageDecoderSetAndroidBitmapFormat == NULL ||
        gAImageDecoderDecodeImage == NULL) {
        dlclose(gAndroidImageDecoderHandle);
        gAndroidImageDecoderHandle = NULL;
        return false;
    }
    return true;
}

static bool termux_ghostty_decode_png(void* TERMUX_UNUSED(userdata), const void* allocator,
                                      const uint8_t* data, size_t data_len, GhosttySysImage* out) {
    if (out == NULL)
        return false;
    memset(out, 0, sizeof(*out));

    if (data == NULL || data_len == 0 || !load_android_image_decoder_symbols() ||
        gGhosttyAlloc == NULL || gGhosttyFree == NULL) {
        return false;
    }

    AImageDecoder* decoder = NULL;
    if (gAImageDecoderCreateFromBuffer(data, data_len, &decoder) != ANDROID_IMAGE_DECODER_SUCCESS ||
        decoder == NULL) {
        return false;
    }

    bool success = false;
    uint8_t* pixels = NULL;
    size_t pixels_len = 0;
    do {
        AImageDecoderHeaderInfo header = gAImageDecoderGetHeaderInfo(decoder);
        if (header == NULL)
            break;

        int32_t width = gAImageDecoderHeaderInfoGetWidth(header);
        int32_t height = gAImageDecoderHeaderInfoGetHeight(header);
        if (width <= 0 || height <= 0)
            break;

        size_t stride = (size_t) width * 4u;
        if (stride / 4u != (size_t) width || (size_t) height > SIZE_MAX / stride)
            break;
        pixels_len = stride * (size_t) height;

        if (gAImageDecoderSetAndroidBitmapFormat(decoder, ANDROID_BITMAP_FORMAT_RGBA_8888) !=
            ANDROID_IMAGE_DECODER_SUCCESS) {
            break;
        }
        if (gAImageDecoderSetUnpremultipliedRequired != NULL)
            gAImageDecoderSetUnpremultipliedRequired(decoder, true);

        pixels = gGhosttyAlloc(allocator, pixels_len);
        if (pixels == NULL)
            break;

        if (gAImageDecoderDecodeImage(decoder, pixels, stride, pixels_len) !=
            ANDROID_IMAGE_DECODER_SUCCESS) {
            gGhosttyFree(allocator, pixels, pixels_len);
            pixels = NULL;
            break;
        }

        out->width = (uint32_t) width;
        out->height = (uint32_t) height;
        out->data = pixels;
        out->data_len = pixels_len;
        pixels = NULL;
        success = true;
    } while (false);

    if (pixels != NULL)
        gGhosttyFree(allocator, pixels, pixels_len);
    gAImageDecoderDelete(decoder);
    return success;
}

static bool install_ghostty_sys_callbacks(void) {
    if (gGhosttySysCallbacksInstalled)
        return true;
    if (gGhosttySysSet == NULL)
        return false;

    GhosttyResult result = gGhosttySysSet(GHOSTTY_SYS_OPT_DECODE_PNG,
                                          (const void*) termux_ghostty_decode_png);
    if (result != 0)
        return false;
    gGhosttySysCallbacksInstalled = true;
    return true;
}

static bool load_ghostty_symbols(void) {
    if (gGhosttyHandle != NULL) return true;

    gGhosttyHandle = dlopen("libghostty-vt.so", RTLD_NOW | RTLD_LOCAL);
    if (gGhosttyHandle == NULL)
        return false;

    gTerminalNew = (ghostty_terminal_new_fn) load_symbol("ghostty_terminal_new");
    gTerminalFree = (ghostty_terminal_free_fn) load_symbol("ghostty_terminal_free");
    gTerminalReset = (ghostty_terminal_reset_fn) load_symbol("ghostty_terminal_reset");
    gTerminalResize = (ghostty_terminal_resize_fn) load_symbol("ghostty_terminal_resize");
    gTerminalVtWrite = (ghostty_terminal_vt_write_fn) load_symbol("ghostty_terminal_vt_write");
    gTerminalSet = (ghostty_terminal_set_fn) load_symbol("ghostty_terminal_set");
    gTerminalScrollViewport = (ghostty_terminal_scroll_viewport_fn) load_symbol("ghostty_terminal_scroll_viewport");
    gTerminalGet = (ghostty_terminal_get_fn) load_symbol("ghostty_terminal_get");
    gTerminalModeGet = (ghostty_terminal_mode_get_fn) load_symbol("ghostty_terminal_mode_get");
    gTerminalGridRef = (ghostty_terminal_grid_ref_fn) load_symbol("ghostty_terminal_grid_ref");
    gTerminalSelectWord = (ghostty_terminal_select_word_fn) load_symbol("ghostty_terminal_select_word");
    gTerminalPointFromGridRef = (ghostty_terminal_point_from_grid_ref_fn) load_symbol("ghostty_terminal_point_from_grid_ref");
    gTerminalSelectionFormatBuf = (ghostty_terminal_selection_format_buf_fn) load_symbol("ghostty_terminal_selection_format_buf");
    gGridRefHyperlinkUri = (ghostty_grid_ref_hyperlink_uri_fn) load_symbol("ghostty_grid_ref_hyperlink_uri");
    gRenderStateNew = (ghostty_render_state_new_fn) load_symbol("ghostty_render_state_new");
    gRenderStateFree = (ghostty_render_state_free_fn) load_symbol("ghostty_render_state_free");
    gRenderStateUpdate = (ghostty_render_state_update_fn) load_symbol("ghostty_render_state_update");
    gRenderStateGet = (ghostty_render_state_get_fn) load_symbol("ghostty_render_state_get");
    gRenderStateSet = (ghostty_render_state_set_fn) load_symbol("ghostty_render_state_set");
    gRenderStateColorsGet = (ghostty_render_state_colors_get_fn) load_symbol("ghostty_render_state_colors_get");
    gRenderStateRowIteratorNew = (ghostty_render_state_row_iterator_new_fn) load_symbol("ghostty_render_state_row_iterator_new");
    gRenderStateRowIteratorFree = (ghostty_render_state_row_iterator_free_fn) load_symbol("ghostty_render_state_row_iterator_free");
    gRenderStateRowIteratorNext = (ghostty_render_state_row_iterator_next_fn) load_symbol("ghostty_render_state_row_iterator_next");
    gRenderStateRowGet = (ghostty_render_state_row_get_fn) load_symbol("ghostty_render_state_row_get");
    gRenderStateRowSet = (ghostty_render_state_row_set_fn) load_symbol("ghostty_render_state_row_set");
    gRenderStateRowCellsNew = (ghostty_render_state_row_cells_new_fn) load_symbol("ghostty_render_state_row_cells_new");
    gRenderStateRowCellsFree = (ghostty_render_state_row_cells_free_fn) load_symbol("ghostty_render_state_row_cells_free");
    gRenderStateRowCellsNext = (ghostty_render_state_row_cells_next_fn) load_symbol("ghostty_render_state_row_cells_next");
    gRenderStateRowCellsGet = (ghostty_render_state_row_cells_get_fn) load_symbol("ghostty_render_state_row_cells_get");
    gKittyGraphicsGet = (ghostty_kitty_graphics_get_fn) load_symbol("ghostty_kitty_graphics_get");
    gKittyGraphicsImage = (ghostty_kitty_graphics_image_fn) load_symbol("ghostty_kitty_graphics_image");
    gKittyGraphicsImageGet = (ghostty_kitty_graphics_image_get_fn) load_symbol("ghostty_kitty_graphics_image_get");
    gKittyGraphicsPlacementIteratorNew =
        (ghostty_kitty_graphics_placement_iterator_new_fn) load_symbol("ghostty_kitty_graphics_placement_iterator_new");
    gKittyGraphicsPlacementIteratorFree =
        (ghostty_kitty_graphics_placement_iterator_free_fn) load_symbol("ghostty_kitty_graphics_placement_iterator_free");
    gKittyGraphicsPlacementNext = (ghostty_kitty_graphics_placement_next_fn) load_symbol("ghostty_kitty_graphics_placement_next");
    gKittyGraphicsPlacementGet = (ghostty_kitty_graphics_placement_get_fn) load_symbol("ghostty_kitty_graphics_placement_get");
    gKittyGraphicsPlacementRenderInfo =
        (ghostty_kitty_graphics_placement_render_info_fn) load_symbol("ghostty_kitty_graphics_placement_render_info");
    gGhosttySysSet = (ghostty_sys_set_fn) load_symbol("ghostty_sys_set");
    gGhosttyAlloc = (ghostty_alloc_fn) load_symbol("ghostty_alloc");
    gGhosttyFree = (ghostty_free_fn) load_symbol("ghostty_free");
    gCellGet = (ghostty_cell_get_fn) load_symbol("ghostty_cell_get");
    gKeyEncoderNew = (ghostty_key_encoder_new_fn) load_symbol("ghostty_key_encoder_new");
    gKeyEncoderFree = (ghostty_key_encoder_free_fn) load_symbol("ghostty_key_encoder_free");
    gKeyEncoderSetoptFromTerminal = (ghostty_key_encoder_setopt_from_terminal_fn) load_symbol("ghostty_key_encoder_setopt_from_terminal");
    gKeyEncoderEncode = (ghostty_key_encoder_encode_fn) load_symbol("ghostty_key_encoder_encode");
    gKeyEventNew = (ghostty_key_event_new_fn) load_symbol("ghostty_key_event_new");
    gKeyEventFree = (ghostty_key_event_free_fn) load_symbol("ghostty_key_event_free");
    gKeyEventSetAction = (ghostty_key_event_set_action_fn) load_symbol("ghostty_key_event_set_action");
    gKeyEventSetKey = (ghostty_key_event_set_key_fn) load_symbol("ghostty_key_event_set_key");
    gKeyEventSetMods = (ghostty_key_event_set_mods_fn) load_symbol("ghostty_key_event_set_mods");
    gKeyEventSetUtf8 = (ghostty_key_event_set_utf8_fn) load_symbol("ghostty_key_event_set_utf8");
    gFocusEncode = (ghostty_focus_encode_fn) load_symbol("ghostty_focus_encode");
    gPasteEncode = (ghostty_paste_encode_fn) load_symbol("ghostty_paste_encode");
    gMouseEncoderNew = (ghostty_mouse_encoder_new_fn) load_symbol("ghostty_mouse_encoder_new");
    gMouseEncoderFree = (ghostty_mouse_encoder_free_fn) load_symbol("ghostty_mouse_encoder_free");
    gMouseEncoderSetopt = (ghostty_mouse_encoder_setopt_fn) load_symbol("ghostty_mouse_encoder_setopt");
    gMouseEncoderSetoptFromTerminal = (ghostty_mouse_encoder_setopt_from_terminal_fn) load_symbol("ghostty_mouse_encoder_setopt_from_terminal");
    gMouseEncoderEncode = (ghostty_mouse_encoder_encode_fn) load_symbol("ghostty_mouse_encoder_encode");
    gMouseEventNew = (ghostty_mouse_event_new_fn) load_symbol("ghostty_mouse_event_new");
    gMouseEventFree = (ghostty_mouse_event_free_fn) load_symbol("ghostty_mouse_event_free");
    gMouseEventSetAction = (ghostty_mouse_event_set_action_fn) load_symbol("ghostty_mouse_event_set_action");
    gMouseEventSetButton = (ghostty_mouse_event_set_button_fn) load_symbol("ghostty_mouse_event_set_button");
    gMouseEventSetPosition = (ghostty_mouse_event_set_position_fn) load_symbol("ghostty_mouse_event_set_position");

    if (gTerminalNew == NULL || gTerminalFree == NULL || gTerminalReset == NULL ||
        gTerminalResize == NULL || gTerminalVtWrite == NULL || gTerminalSet == NULL ||
        gTerminalScrollViewport == NULL || gTerminalGet == NULL ||
        gTerminalModeGet == NULL || gTerminalGridRef == NULL || gTerminalSelectWord == NULL ||
        gTerminalPointFromGridRef == NULL || gTerminalSelectionFormatBuf == NULL ||
        gGridRefHyperlinkUri == NULL ||
        gRenderStateNew == NULL || gRenderStateFree == NULL || gRenderStateUpdate == NULL ||
        gRenderStateGet == NULL || gRenderStateSet == NULL || gRenderStateColorsGet == NULL || gRenderStateRowIteratorNew == NULL ||
        gRenderStateRowIteratorFree == NULL || gRenderStateRowIteratorNext == NULL ||
        gRenderStateRowGet == NULL || gRenderStateRowSet == NULL || gRenderStateRowCellsNew == NULL ||
        gRenderStateRowCellsFree == NULL || gRenderStateRowCellsNext == NULL ||
        gRenderStateRowCellsGet == NULL || gKittyGraphicsGet == NULL ||
        gKittyGraphicsImage == NULL || gKittyGraphicsImageGet == NULL ||
        gKittyGraphicsPlacementIteratorNew == NULL || gKittyGraphicsPlacementIteratorFree == NULL ||
        gKittyGraphicsPlacementNext == NULL || gKittyGraphicsPlacementGet == NULL ||
        gKittyGraphicsPlacementRenderInfo == NULL || gGhosttySysSet == NULL ||
        gGhosttyAlloc == NULL || gGhosttyFree == NULL || gCellGet == NULL ||
        gKeyEncoderNew == NULL || gKeyEncoderFree == NULL ||
        gKeyEncoderSetoptFromTerminal == NULL || gKeyEncoderEncode == NULL || gKeyEventNew == NULL ||
        gKeyEventFree == NULL || gKeyEventSetAction == NULL || gKeyEventSetKey == NULL ||
        gKeyEventSetMods == NULL || gKeyEventSetUtf8 == NULL || gFocusEncode == NULL || gPasteEncode == NULL ||
        gMouseEncoderNew == NULL || gMouseEncoderFree == NULL ||
        gMouseEncoderSetopt == NULL || gMouseEncoderSetoptFromTerminal == NULL ||
        gMouseEncoderEncode == NULL || gMouseEventNew == NULL || gMouseEventFree == NULL ||
        gMouseEventSetAction == NULL || gMouseEventSetButton == NULL ||
        gMouseEventSetPosition == NULL) {
        dlclose(gGhosttyHandle);
        gGhosttyHandle = NULL;
        return false;
    }

    return true;
}

static GhosttyBridgeContext* require_context(JNIEnv* env, jlong context) {
    GhosttyBridgeContext* bridge = (GhosttyBridgeContext*) (uintptr_t) context;
    if (bridge == NULL || bridge->terminal == NULL) {
        throw_runtime_exception(env, "Invalid libghostty-vt terminal context");
        return NULL;
    }
    return bridge;
}

static bool append_pending_pty_write(GhosttyBridgeContext* bridge, const uint8_t* data, size_t len) {
    if (bridge == NULL || data == NULL || len == 0) return true;
    if (len > SIZE_MAX - bridge->pending_pty_write_len) return false;

    size_t required = bridge->pending_pty_write_len + len;
    if (required > bridge->pending_pty_write_cap) {
        size_t nextCap = bridge->pending_pty_write_cap == 0 ? 4096 : bridge->pending_pty_write_cap;
        while (nextCap < required) {
            if (nextCap > SIZE_MAX / 2) {
                nextCap = required;
                break;
            }
            nextCap *= 2;
        }
        uint8_t* next = (uint8_t*) realloc(bridge->pending_pty_write, nextCap);
        if (next == NULL) return false;
        bridge->pending_pty_write = next;
        bridge->pending_pty_write_cap = nextCap;
    }

    memcpy(bridge->pending_pty_write + bridge->pending_pty_write_len, data, len);
    bridge->pending_pty_write_len = required;
    return true;
}

static void termux_ghostty_write_pty_callback(GhosttyTerminal TERMUX_UNUSED(terminal),
                                              void* userdata,
                                              const uint8_t* data,
                                              size_t len) {
    GhosttyBridgeContext* bridge = (GhosttyBridgeContext*) userdata;
    if (!append_pending_pty_write(bridge, data, len) && bridge != NULL)
        bridge->pending_pty_write_oom = true;
}

static void termux_ghostty_bell_callback(GhosttyTerminal TERMUX_UNUSED(terminal), void* userdata) {
    GhosttyBridgeContext* bridge = (GhosttyBridgeContext*) userdata;
    if (bridge == NULL) return;
    if (bridge->pending_bell_count != UINT32_MAX)
        bridge->pending_bell_count++;
}

static void termux_ghostty_title_changed_callback(GhosttyTerminal TERMUX_UNUSED(terminal), void* userdata) {
    GhosttyBridgeContext* bridge = (GhosttyBridgeContext*) userdata;
    if (bridge != NULL)
        bridge->pending_title_changed = true;
}

static GhosttyString termux_ghostty_enquiry_callback(GhosttyTerminal TERMUX_UNUSED(terminal),
                                                     void* TERMUX_UNUSED(userdata)) {
    static const uint8_t response[] = "";
    GhosttyString result = { .ptr = response, .len = 0 };
    return result;
}

static GhosttyString termux_ghostty_xtversion_callback(GhosttyTerminal TERMUX_UNUSED(terminal),
                                                       void* TERMUX_UNUSED(userdata)) {
    static const uint8_t response[] = "Termux libghostty-vt";
    GhosttyString result = { .ptr = response, .len = sizeof(response) - 1 };
    return result;
}

static bool termux_ghostty_color_scheme_callback(GhosttyTerminal TERMUX_UNUSED(terminal),
                                                 void* TERMUX_UNUSED(userdata),
                                                 int* out_scheme) {
    if (out_scheme == NULL) return false;
    *out_scheme = 1;
    return true;
}

static bool termux_ghostty_size_callback(GhosttyTerminal TERMUX_UNUSED(terminal),
                                         void* userdata,
                                         GhosttySizeReportSize* out_size) {
    GhosttyBridgeContext* bridge = (GhosttyBridgeContext*) userdata;
    if (bridge == NULL || out_size == NULL) return false;
    out_size->rows = bridge->rows;
    out_size->columns = bridge->columns;
    out_size->cell_width = bridge->cell_width_pixels;
    out_size->cell_height = bridge->cell_height_pixels;
    return bridge->rows != 0 && bridge->columns != 0;
}

static bool termux_ghostty_device_attributes_callback(GhosttyTerminal TERMUX_UNUSED(terminal),
                                                      void* TERMUX_UNUSED(userdata),
                                                      GhosttyDeviceAttributes* out_attrs) {
    if (out_attrs == NULL) return false;
    memset(out_attrs, 0, sizeof(*out_attrs));

    static const uint16_t primary_features[] = {
        GHOSTTY_DA_FEATURE_COLUMNS_132,
        GHOSTTY_DA_FEATURE_PRINTER,
        GHOSTTY_DA_FEATURE_SELECTIVE_ERASE,
        GHOSTTY_DA_FEATURE_NATIONAL_REPLACEMENT,
        GHOSTTY_DA_FEATURE_TECHNICAL_CHARACTERS,
        GHOSTTY_DA_FEATURE_WINDOWING,
        GHOSTTY_DA_FEATURE_HORIZONTAL_SCROLLING,
        GHOSTTY_DA_FEATURE_ANSI_COLOR,
    };

    out_attrs->primary.conformance_level = GHOSTTY_DA_CONFORMANCE_VT420;
    out_attrs->primary.num_features = sizeof(primary_features) / sizeof(primary_features[0]);
    memcpy(out_attrs->primary.features, primary_features, sizeof(primary_features));

    out_attrs->secondary.device_type = GHOSTTY_DA_DEVICE_TYPE_VT420;
    out_attrs->secondary.firmware_version = 320;
    out_attrs->secondary.rom_cartridge = 0;
    out_attrs->tertiary.unit_id = 0;
    return true;
}

static bool set_terminal_callback(GhosttyTerminal terminal, int option, const void* value) {
    return gTerminalSet != NULL && gTerminalSet(terminal, option, value) == 0;
}

static GhosttyMods key_mods_from_termux(jint key_mod) {
    GhosttyMods mods = 0;
    if ((key_mod & TERMUX_KEYMOD_SHIFT) != 0) mods |= GHOSTTY_MODS_SHIFT;
    if ((key_mod & TERMUX_KEYMOD_CTRL) != 0) mods |= GHOSTTY_MODS_CTRL;
    if ((key_mod & TERMUX_KEYMOD_ALT) != 0) mods |= GHOSTTY_MODS_ALT;
    if ((key_mod & TERMUX_KEYMOD_NUM_LOCK) != 0) mods |= GHOSTTY_MODS_NUM_LOCK;
    return mods;
}

static bool ghostty_key_from_android(jint key_code, jint key_mod, GhosttyKey* key) {
    switch (key_code) {
        case AKEYCODE_DPAD_CENTER: *key = GHOSTTY_KEY_ENTER; return true;
        case AKEYCODE_DPAD_UP: *key = GHOSTTY_KEY_ARROW_UP; return true;
        case AKEYCODE_DPAD_DOWN: *key = GHOSTTY_KEY_ARROW_DOWN; return true;
        case AKEYCODE_DPAD_RIGHT: *key = GHOSTTY_KEY_ARROW_RIGHT; return true;
        case AKEYCODE_DPAD_LEFT: *key = GHOSTTY_KEY_ARROW_LEFT; return true;
        case AKEYCODE_MOVE_HOME: *key = GHOSTTY_KEY_HOME; return true;
        case AKEYCODE_MOVE_END: *key = GHOSTTY_KEY_END; return true;
        case AKEYCODE_F1: *key = GHOSTTY_KEY_F1; return true;
        case AKEYCODE_F2: *key = GHOSTTY_KEY_F2; return true;
        case AKEYCODE_F3: *key = GHOSTTY_KEY_F3; return true;
        case AKEYCODE_F4: *key = GHOSTTY_KEY_F4; return true;
        case AKEYCODE_F5: *key = GHOSTTY_KEY_F5; return true;
        case AKEYCODE_F6: *key = GHOSTTY_KEY_F6; return true;
        case AKEYCODE_F7: *key = GHOSTTY_KEY_F7; return true;
        case AKEYCODE_F8: *key = GHOSTTY_KEY_F8; return true;
        case AKEYCODE_F9: *key = GHOSTTY_KEY_F9; return true;
        case AKEYCODE_F10: *key = GHOSTTY_KEY_F10; return true;
        case AKEYCODE_F11: *key = GHOSTTY_KEY_F11; return true;
        case AKEYCODE_F12: *key = GHOSTTY_KEY_F12; return true;
        case AKEYCODE_SYSRQ: *key = GHOSTTY_KEY_PRINT_SCREEN; return true;
        case AKEYCODE_BREAK: *key = GHOSTTY_KEY_PAUSE; return true;
        case AKEYCODE_ESCAPE:
        case AKEYCODE_BACK:
            *key = GHOSTTY_KEY_ESCAPE;
            return true;
        case AKEYCODE_INSERT: *key = GHOSTTY_KEY_INSERT; return true;
        case AKEYCODE_FORWARD_DEL: *key = GHOSTTY_KEY_DELETE; return true;
        case AKEYCODE_PAGE_UP: *key = GHOSTTY_KEY_PAGE_UP; return true;
        case AKEYCODE_PAGE_DOWN: *key = GHOSTTY_KEY_PAGE_DOWN; return true;
        case AKEYCODE_DEL: *key = GHOSTTY_KEY_BACKSPACE; return true;
        case AKEYCODE_NUM_LOCK: *key = GHOSTTY_KEY_NUM_LOCK; return true;
        case AKEYCODE_SPACE:
            if ((key_mod & TERMUX_KEYMOD_CTRL) == 0) return false;
            *key = GHOSTTY_KEY_SPACE;
            return true;
        case AKEYCODE_TAB: *key = GHOSTTY_KEY_TAB; return true;
        case AKEYCODE_ENTER: *key = GHOSTTY_KEY_ENTER; return true;
        case AKEYCODE_NUMPAD_ENTER: *key = GHOSTTY_KEY_NUMPAD_ENTER; return true;
        case AKEYCODE_NUMPAD_MULTIPLY: *key = GHOSTTY_KEY_NUMPAD_MULTIPLY; return true;
        case AKEYCODE_NUMPAD_ADD: *key = GHOSTTY_KEY_NUMPAD_ADD; return true;
        case AKEYCODE_NUMPAD_COMMA: *key = GHOSTTY_KEY_NUMPAD_COMMA; return true;
        case AKEYCODE_NUMPAD_DOT: *key = GHOSTTY_KEY_NUMPAD_DECIMAL; return true;
        case AKEYCODE_NUMPAD_SUBTRACT: *key = GHOSTTY_KEY_NUMPAD_SUBTRACT; return true;
        case AKEYCODE_NUMPAD_DIVIDE: *key = GHOSTTY_KEY_NUMPAD_DIVIDE; return true;
        case AKEYCODE_NUMPAD_0: *key = GHOSTTY_KEY_NUMPAD_0; return true;
        case AKEYCODE_NUMPAD_1: *key = GHOSTTY_KEY_NUMPAD_1; return true;
        case AKEYCODE_NUMPAD_2: *key = GHOSTTY_KEY_NUMPAD_2; return true;
        case AKEYCODE_NUMPAD_3: *key = GHOSTTY_KEY_NUMPAD_3; return true;
        case AKEYCODE_NUMPAD_4: *key = GHOSTTY_KEY_NUMPAD_4; return true;
        case AKEYCODE_NUMPAD_5: *key = GHOSTTY_KEY_NUMPAD_5; return true;
        case AKEYCODE_NUMPAD_6: *key = GHOSTTY_KEY_NUMPAD_6; return true;
        case AKEYCODE_NUMPAD_7: *key = GHOSTTY_KEY_NUMPAD_7; return true;
        case AKEYCODE_NUMPAD_8: *key = GHOSTTY_KEY_NUMPAD_8; return true;
        case AKEYCODE_NUMPAD_9: *key = GHOSTTY_KEY_NUMPAD_9; return true;
        case AKEYCODE_NUMPAD_EQUALS: *key = GHOSTTY_KEY_NUMPAD_EQUAL; return true;
        default:
            return false;
    }
}

static bool ghostty_key_from_codepoint(jint codepoint, GhosttyKey* key) {
    if (codepoint >= 'a' && codepoint <= 'z') {
        *key = (GhosttyKey) (GHOSTTY_KEY_A + (codepoint - 'a'));
        return true;
    }
    if (codepoint >= 'A' && codepoint <= 'Z') {
        *key = (GhosttyKey) (GHOSTTY_KEY_A + (codepoint - 'A'));
        return true;
    }
    if (codepoint >= '0' && codepoint <= '9') {
        *key = (GhosttyKey) (GHOSTTY_KEY_DIGIT_0 + (codepoint - '0'));
        return true;
    }

    switch (codepoint) {
        case '`': *key = GHOSTTY_KEY_BACKQUOTE; return true;
        case '\\': *key = GHOSTTY_KEY_BACKSLASH; return true;
        case '[': *key = GHOSTTY_KEY_BRACKET_LEFT; return true;
        case ']': *key = GHOSTTY_KEY_BRACKET_RIGHT; return true;
        case ',': *key = GHOSTTY_KEY_COMMA; return true;
        case '=': *key = GHOSTTY_KEY_EQUAL; return true;
        case '-': *key = GHOSTTY_KEY_MINUS; return true;
        case '.': *key = GHOSTTY_KEY_PERIOD; return true;
        case '\'': *key = GHOSTTY_KEY_QUOTE; return true;
        case ';': *key = GHOSTTY_KEY_SEMICOLON; return true;
        case '/': *key = GHOSTTY_KEY_SLASH; return true;
        case ' ': *key = GHOSTTY_KEY_SPACE; return true;
        case '\t': *key = GHOSTTY_KEY_TAB; return true;
        case '\r':
        case '\n':
            *key = GHOSTTY_KEY_ENTER;
            return true;
        case 27:
            *key = GHOSTTY_KEY_ESCAPE;
            return true;
        case 127:
            *key = GHOSTTY_KEY_BACKSPACE;
            return true;
        default:
            *key = GHOSTTY_KEY_UNIDENTIFIED;
            return true;
    }
}

static bool utf8_from_codepoint(jint codepoint, char* output, size_t* output_len) {
    if (output == NULL || output_len == NULL) return false;
    *output_len = 0;
    if (codepoint < 0 || codepoint > 0x10ffff || (codepoint >= 0xd800 && codepoint <= 0xdfff))
        return false;
    if (codepoint < 0x20 || codepoint == 0x7f)
        return true;

    if (codepoint <= 0x7f) {
        output[0] = (char) codepoint;
        *output_len = 1;
    } else if (codepoint <= 0x7ff) {
        output[0] = (char) (0xc0 | (codepoint >> 6));
        output[1] = (char) (0x80 | (codepoint & 0x3f));
        *output_len = 2;
    } else if (codepoint <= 0xffff) {
        output[0] = (char) (0xe0 | (codepoint >> 12));
        output[1] = (char) (0x80 | ((codepoint >> 6) & 0x3f));
        output[2] = (char) (0x80 | (codepoint & 0x3f));
        *output_len = 3;
    } else {
        output[0] = (char) (0xf0 | (codepoint >> 18));
        output[1] = (char) (0x80 | ((codepoint >> 12) & 0x3f));
        output[2] = (char) (0x80 | ((codepoint >> 6) & 0x3f));
        output[3] = (char) (0x80 | (codepoint & 0x3f));
        *output_len = 4;
    }
    return true;
}

static bool ghostty_mouse_from_termux(jint mouse_button, jboolean pressed, int* action, int* button) {
    switch (mouse_button) {
        case 0:
            *action = pressed ? GHOSTTY_MOUSE_ACTION_PRESS : GHOSTTY_MOUSE_ACTION_RELEASE;
            *button = GHOSTTY_MOUSE_BUTTON_LEFT;
            return true;
        case 32:
            *action = GHOSTTY_MOUSE_ACTION_MOTION;
            *button = GHOSTTY_MOUSE_BUTTON_LEFT;
            return true;
        case 64:
            *action = GHOSTTY_MOUSE_ACTION_PRESS;
            *button = GHOSTTY_MOUSE_BUTTON_FOUR;
            return true;
        case 65:
            *action = GHOSTTY_MOUSE_ACTION_PRESS;
            *button = GHOSTTY_MOUSE_BUTTON_FIVE;
            return true;
        default:
            return false;
    }
}

static bool ghostty_grid_ref_from_termux(JNIEnv* env, GhosttyBridgeContext* bridge, jint x, jint y, GhosttyGridRef* out_ref) {
    uint16_t columns = 0;
    uint16_t rows = 0;
    uint64_t scrollback_rows = 0;
    if (gTerminalGet(bridge->terminal, 1, &columns) != 0 || columns == 0 ||
        gTerminalGet(bridge->terminal, 2, &rows) != 0 || rows == 0 ||
        gTerminalGet(bridge->terminal, 15, &scrollback_rows) != 0) {
        return false;
    }

    if (x < 0) x = 0;
    if (x >= columns) x = (jint) columns - 1;

    int64_t screen_y = (int64_t) scrollback_rows + (int64_t) y;
    int64_t last_y = (int64_t) scrollback_rows + (int64_t) rows - 1;
    if (screen_y < 0) screen_y = 0;
    if (screen_y > last_y) screen_y = last_y;
    if (screen_y > UINT32_MAX) return false;

    GhosttyPoint point = {
        .tag = GHOSTTY_POINT_TAG_SCREEN,
        .value = { .coordinate = { .x = (uint16_t) x, .y = (uint32_t) screen_y } },
    };
    out_ref->size = sizeof(GhosttyGridRef);
    out_ref->node = NULL;
    out_ref->x = 0;
    out_ref->y = 0;
    GhosttyResult result = gTerminalGridRef(bridge->terminal, point, out_ref);
    if (result != 0) {
        if (result != GHOSTTY_NO_VALUE)
            (*env)->ExceptionClear(env);
        return false;
    }
    return true;
}

static jbyteArray ghostty_format_selection_bytes(JNIEnv* env, GhosttyBridgeContext* bridge, const GhosttySelection* selection, bool unwrap, bool trim) {
    GhosttyTerminalSelectionFormatOptions options = {
        .size = sizeof(GhosttyTerminalSelectionFormatOptions),
        .emit = GHOSTTY_FORMATTER_FORMAT_PLAIN,
        .unwrap = unwrap,
        .trim = trim,
        .selection = selection,
    };

    size_t required = 0;
    GhosttyResult result = gTerminalSelectionFormatBuf(bridge->terminal, options, NULL, 0, &required);
    if (result == GHOSTTY_NO_VALUE || required == 0)
        return (*env)->NewByteArray(env, 0);
    if (result != GHOSTTY_OUT_OF_SPACE && result != 0) {
        throw_runtime_exception(env, "Failed to measure libghostty-vt selection text");
        return NULL;
    }

    uint8_t* output = (uint8_t*) malloc(required);
    if (output == NULL) {
        throw_runtime_exception(env, "Failed to allocate libghostty-vt selection text buffer");
        return NULL;
    }

    size_t written = 0;
    result = gTerminalSelectionFormatBuf(bridge->terminal, options, output, required, &written);
    if (result != 0) {
        free(output);
        throw_runtime_exception(env, "Failed to format libghostty-vt selection text");
        return NULL;
    }

    jbyteArray resultArray = (*env)->NewByteArray(env, (jsize) written);
    if (resultArray != NULL)
        (*env)->SetByteArrayRegion(env, resultArray, 0, (jsize) written, (const jbyte*) output);
    free(output);
    return resultArray;
}

static bool ghostty_termux_point_from_grid_ref(GhosttyBridgeContext* bridge, const GhosttyGridRef* ref, jint* x, jint* y) {
    GhosttyPointCoordinate coordinate = {0};
    uint64_t scrollback_rows = 0;
    if (gTerminalGet(bridge->terminal, 15, &scrollback_rows) != 0)
        return false;
    GhosttyResult result = gTerminalPointFromGridRef(bridge->terminal, ref, GHOSTTY_POINT_TAG_SCREEN, &coordinate);
    if (result != 0)
        return false;
    *x = coordinate.x;
    *y = (jint) ((int64_t) coordinate.y - (int64_t) scrollback_rows);
    return true;
}

JNIEXPORT jboolean JNICALL Java_com_termux_terminal_JNI_ghosttyIsAvailable(
        JNIEnv* TERMUX_UNUSED(env),
        jclass TERMUX_UNUSED(clazz))
{
    return load_ghostty_symbols() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_com_termux_terminal_JNI_ghosttyCreate(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jint columns,
        jint rows,
        jint maxScrollback,
        jint cellWidthPixels,
        jint cellHeightPixels)
{
    if (!load_ghostty_symbols()) {
        throw_runtime_exception(env, "libghostty-vt.so is not available");
        return 0;
    }
    if (!install_ghostty_sys_callbacks()) {
        throw_runtime_exception(env, "Failed to install libghostty-vt system callbacks");
        return 0;
    }

    GhosttyBridgeContext* bridge = (GhosttyBridgeContext*) calloc(1, sizeof(GhosttyBridgeContext));
    if (bridge == NULL) {
        throw_runtime_exception(env, "Failed to allocate libghostty-vt bridge context");
        return 0;
    }
    bridge->columns = (uint16_t) columns;
    bridge->rows = (uint16_t) rows;
    bridge->cell_width_pixels = (uint32_t) cellWidthPixels;
    bridge->cell_height_pixels = (uint32_t) cellHeightPixels;

    GhosttyTerminalOptions options = {
        .cols = (uint16_t) columns,
        .rows = (uint16_t) rows,
        .max_scrollback = (size_t) maxScrollback,
    };

    GhosttyResult result = gTerminalNew(NULL, &bridge->terminal, options);
    if (result != 0 || bridge->terminal == NULL) {
        free(bridge);
        throw_runtime_exception(env, "Failed to create libghostty-vt terminal");
        return 0;
    }

    if (!set_terminal_callback(bridge->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, bridge) ||
        !set_terminal_callback(bridge->terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY,
                               (const void*) termux_ghostty_write_pty_callback) ||
        !set_terminal_callback(bridge->terminal, GHOSTTY_TERMINAL_OPT_BELL,
                               (const void*) termux_ghostty_bell_callback) ||
        !set_terminal_callback(bridge->terminal, GHOSTTY_TERMINAL_OPT_ENQUIRY,
                               (const void*) termux_ghostty_enquiry_callback) ||
        !set_terminal_callback(bridge->terminal, GHOSTTY_TERMINAL_OPT_XTVERSION,
                               (const void*) termux_ghostty_xtversion_callback) ||
        !set_terminal_callback(bridge->terminal, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED,
                               (const void*) termux_ghostty_title_changed_callback) ||
        !set_terminal_callback(bridge->terminal, GHOSTTY_TERMINAL_OPT_SIZE,
                               (const void*) termux_ghostty_size_callback) ||
        !set_terminal_callback(bridge->terminal, GHOSTTY_TERMINAL_OPT_COLOR_SCHEME,
                               (const void*) termux_ghostty_color_scheme_callback) ||
        !set_terminal_callback(bridge->terminal, GHOSTTY_TERMINAL_OPT_DEVICE_ATTRIBUTES,
                               (const void*) termux_ghostty_device_attributes_callback)) {
        gTerminalFree(bridge->terminal);
        free(bridge);
        throw_runtime_exception(env, "Failed to install libghostty-vt terminal callbacks");
        return 0;
    }

    uint64_t kitty_image_storage_limit = 128ull * 1024ull * 1024ull;
    gTerminalSet(bridge->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT, &kitty_image_storage_limit);

    result = gTerminalResize(bridge->terminal, (uint16_t) columns, (uint16_t) rows,
                             (uint32_t) cellWidthPixels, (uint32_t) cellHeightPixels);
    if (result != 0) {
        gTerminalFree(bridge->terminal);
        free(bridge);
        throw_runtime_exception(env, "Failed to resize libghostty-vt terminal");
        return 0;
    }

    result = gRenderStateNew(NULL, &bridge->render_state);
    if (result != 0 || bridge->render_state == NULL) {
        gTerminalFree(bridge->terminal);
        free(bridge);
        throw_runtime_exception(env, "Failed to create libghostty-vt render state");
        return 0;
    }

    return (jlong) (uintptr_t) bridge;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_ghosttyFree(
        JNIEnv* TERMUX_UNUSED(env),
        jclass TERMUX_UNUSED(clazz),
        jlong context)
{
    GhosttyBridgeContext* bridge = (GhosttyBridgeContext*) (uintptr_t) context;
    if (bridge == NULL) return;
    if (bridge->render_state != NULL && load_ghostty_symbols())
        gRenderStateFree(bridge->render_state);
    if (bridge->terminal != NULL && load_ghostty_symbols())
        gTerminalFree(bridge->terminal);
    free(bridge->pending_pty_write);
    free(bridge);
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_ghosttyResize(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jint columns,
        jint rows,
        jint cellWidthPixels,
        jint cellHeightPixels)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return;
    GhosttyResult result = gTerminalResize(bridge->terminal, (uint16_t) columns, (uint16_t) rows,
                                           (uint32_t) cellWidthPixels, (uint32_t) cellHeightPixels);
    if (result != 0)
        throw_runtime_exception(env, "Failed to resize libghostty-vt terminal");
    else {
        bridge->columns = (uint16_t) columns;
        bridge->rows = (uint16_t) rows;
        bridge->cell_width_pixels = (uint32_t) cellWidthPixels;
        bridge->cell_height_pixels = (uint32_t) cellHeightPixels;
    }
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_ghosttyWrite(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jbyteArray data,
        jint offset,
        jint count)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL || data == NULL || count <= 0) return;

    jbyte* bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) {
        throw_runtime_exception(env, "Failed to read VT bytes for libghostty-vt");
        return;
    }

    gTerminalVtWrite(bridge->terminal, (const uint8_t*) bytes + offset, (size_t) count);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT jbyteArray JNICALL Java_com_termux_terminal_JNI_ghosttyDrainPendingPtyWrite(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return NULL;

    if (bridge->pending_pty_write_oom) {
        bridge->pending_pty_write_oom = false;
        throw_runtime_exception(env, "Failed to buffer libghostty-vt PTY response");
        return NULL;
    }

    if (bridge->pending_pty_write_len == 0)
        return NULL;
    if (bridge->pending_pty_write_len > INT32_MAX) {
        bridge->pending_pty_write_len = 0;
        throw_runtime_exception(env, "libghostty-vt PTY response is too large");
        return NULL;
    }

    jsize len = (jsize) bridge->pending_pty_write_len;
    jbyteArray resultArray = (*env)->NewByteArray(env, len);
    if (resultArray == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, resultArray, 0, len, (const jbyte*) bridge->pending_pty_write);
    bridge->pending_pty_write_len = 0;
    return resultArray;
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_ghosttyConsumeBellCount(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return 0;
    uint32_t count = bridge->pending_bell_count;
    bridge->pending_bell_count = 0;
    return count > (uint32_t) INT32_MAX ? INT32_MAX : (jint) count;
}

JNIEXPORT jboolean JNICALL Java_com_termux_terminal_JNI_ghosttyConsumeTitleChanged(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return JNI_FALSE;
    bool changed = bridge->pending_title_changed;
    bridge->pending_title_changed = false;
    return changed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_ghosttyScrollViewport(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jint tag,
        jint deltaRows)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return;

    GhosttyTerminalScrollViewport behavior = {
        .tag = tag,
        .value = { .delta = (intptr_t) deltaRows },
    };
    gTerminalScrollViewport(bridge->terminal, behavior);
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_ghosttyReset(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return;
    gTerminalReset(bridge->terminal);
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_ghosttyGetInt(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jint dataKind)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return 0;

    uint64_t sizeValue = 0;
    uint32_t int32Value = 0;
    uint16_t int16Value = 0;
    int screenValue = 0;
    void* out = &int16Value;

    if (dataKind == 6) out = &screenValue;
    else if (dataKind == 14 || dataKind == 15) out = &sizeValue;
    else if (dataKind == 16 || dataKind == 17) out = &int32Value;

    GhosttyResult result = gTerminalGet(bridge->terminal, dataKind, out);
    if (result != 0) return 0;

    if (dataKind == 6) return screenValue;
    if (dataKind == 14 || dataKind == 15) return (jint) sizeValue;
    if (dataKind == 16 || dataKind == 17) return (jint) int32Value;
    return (jint) int16Value;
}

JNIEXPORT jboolean JNICALL Java_com_termux_terminal_JNI_ghosttyGetBoolean(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jint dataKind)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return JNI_FALSE;
    bool value = false;
    GhosttyResult result = gTerminalGet(bridge->terminal, dataKind, &value);
    return (result == 0 && value) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_termux_terminal_JNI_ghosttyGetMode(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jint mode)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return JNI_FALSE;
    bool value = false;
    GhosttyResult result = gTerminalModeGet(bridge->terminal, (GhosttyMode) mode, &value);
    return (result == 0 && value) ? JNI_TRUE : JNI_FALSE;
}

// Returns the window title as raw standard-UTF-8 bytes. We deliberately do NOT
// use NewStringUTF here: libghostty emits standard UTF-8, but NewStringUTF
// requires JNI Modified UTF-8 (which forbids 4-byte sequences), so a title
// containing any non-BMP character (e.g. an emoji from `printf '\033]2;🚀\a'`)
// would abort under CheckJNI or produce an undefined string on release. The
// Java side decodes these bytes with the standard UTF-8 charset instead.
JNIEXPORT jbyteArray JNICALL Java_com_termux_terminal_JNI_ghosttyGetTitle(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return NULL;

    GhosttyString title = {0};
    GhosttyResult result = gTerminalGet(bridge->terminal, 12, &title);
    if (result != 0 || title.ptr == NULL || title.len == 0) return NULL;
    if (title.len > (size_t) INT32_MAX) return NULL;

    jbyteArray resultArray = (*env)->NewByteArray(env, (jsize) title.len);
    if (resultArray != NULL)
        (*env)->SetByteArrayRegion(env, resultArray, 0, (jsize) title.len, (const jbyte*) title.ptr);
    return resultArray;
}

// libghostty-vt 1.3.0: GHOSTTY_TERMINAL_DATA_PWD = 13. Returns the shell's
// reported working directory (set via OSC 7) as raw standard-UTF-8 bytes.
// Empty when not set; we let callers fall back to /proc/<pid>/cwd in that case.
// Returns bytes rather than a jstring for the same Modified-UTF-8 reason as
// ghosttyGetTitle: a directory name containing a non-BMP character would
// otherwise abort under CheckJNI.
JNIEXPORT jbyteArray JNICALL Java_com_termux_terminal_JNI_ghosttyGetPwd(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return NULL;

    GhosttyString pwd = {0};
    GhosttyResult result = gTerminalGet(bridge->terminal, 13, &pwd);
    if (result != 0 || pwd.ptr == NULL || pwd.len == 0) return NULL;
    if (pwd.len > (size_t) INT32_MAX) return NULL;

    jbyteArray resultArray = (*env)->NewByteArray(env, (jsize) pwd.len);
    if (resultArray != NULL)
        (*env)->SetByteArrayRegion(env, resultArray, 0, (jsize) pwd.len, (const jbyte*) pwd.ptr);
    return resultArray;
}

static jint saturated_jint_from_uint64(uint64_t value) {
    return value > (uint64_t) INT32_MAX ? INT32_MAX : (jint) value;
}

JNIEXPORT jintArray JNICALL Java_com_termux_terminal_JNI_ghosttyGetScrollbar(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return NULL;

    GhosttyTerminalScrollbar scrollbar = {0};
    GhosttyResult result = gTerminalGet(bridge->terminal, 9, &scrollbar);
    if (result != 0) return NULL;

    jint values[3] = {
        saturated_jint_from_uint64(scrollbar.total),
        saturated_jint_from_uint64(scrollbar.offset),
        saturated_jint_from_uint64(scrollbar.len),
    };
    jintArray resultArray = (*env)->NewIntArray(env, 3);
    if (resultArray == NULL) return NULL;
    (*env)->SetIntArrayRegion(env, resultArray, 0, 3, values);
    return resultArray;
}

static jint argb_from_ghostty_rgb(GhosttyColorRgb color) {
    return (jint) (0xff000000u |
                   ((uint32_t) color.r << 16u) |
                   ((uint32_t) color.g << 8u) |
                   (uint32_t) color.b);
}

static GhosttyColorRgb ghostty_rgb_from_argb(jint color) {
    GhosttyColorRgb result = {
        .r = (uint8_t) (((uint32_t) color >> 16u) & 0xffu),
        .g = (uint8_t) (((uint32_t) color >> 8u) & 0xffu),
        .b = (uint8_t) ((uint32_t) color & 0xffu),
    };
    return result;
}

static jint termux_color_from_ghostty_color(GhosttyStyleColor color, jint defaultColor) {
    if (color.tag == GHOSTTY_STYLE_COLOR_PALETTE)
        return (jint) color.value.palette;
    if (color.tag == GHOSTTY_STYLE_COLOR_RGB)
        return argb_from_ghostty_rgb(color.value.rgb);
    return defaultColor;
}

static jint termux_effect_from_ghostty_style(const GhosttyStyle* style) {
    jint effect = 0;
    if (style->bold) effect |= 1;
    if (style->italic) effect |= 1 << 1;
    if (style->underline != 0) effect |= 1 << 2;
    if (style->blink) effect |= 1 << 3;
    if (style->inverse) effect |= 1 << 4;
    if (style->invisible) effect |= 1 << 5;
    if (style->strikethrough) effect |= 1 << 6;
    if (style->faint) effect |= 1 << 8;
    return effect;
}

static jint termux_width_from_ghostty_cell(GhosttyCell cell) {
    int wide = GHOSTTY_CELL_WIDE_NARROW;
    if (gCellGet != NULL && gCellGet(cell, GHOSTTY_CELL_DATA_WIDE, &wide) == 0) {
        switch (wide) {
            case GHOSTTY_CELL_WIDE_WIDE:
                return 2;
            case GHOSTTY_CELL_WIDE_SPACER_TAIL:
            case GHOSTTY_CELL_WIDE_SPACER_HEAD:
                return 0;
            case GHOSTTY_CELL_WIDE_NARROW:
            default:
                return 1;
        }
    }
    return 1;
}

static jint termux_protected_effect_from_ghostty_cell(GhosttyCell cell) {
    bool protected = false;
    if (gCellGet != NULL && gCellGet(cell, GHOSTTY_CELL_DATA_PROTECTED, &protected) == 0 && protected)
        return TERMUX_CHARACTER_ATTRIBUTE_PROTECTED;
    return 0;
}

static jstring jstring_from_codepoints(JNIEnv* env, const uint32_t* codepoints, size_t count) {
    if (codepoints == NULL || count == 0)
        return NULL;
    jchar* chars = (jchar*) calloc(count * 2u, sizeof(jchar));
    if (chars == NULL) {
        throw_runtime_exception(env, "Failed to allocate libghostty-vt grapheme string");
        return NULL;
    }

    size_t length = 0;
    for (size_t i = 0; i < count; i++) {
        uint32_t codepoint = codepoints[i];
        if (codepoint == 0)
            continue;
        if (codepoint > 0x10ffffu || (codepoint >= 0xd800u && codepoint <= 0xdfffu))
            codepoint = 0xfffdu;
        if (codepoint <= 0xffffu) {
            chars[length++] = (jchar) codepoint;
        } else {
            codepoint -= 0x10000u;
            chars[length++] = (jchar) (0xd800u + (codepoint >> 10u));
            chars[length++] = (jchar) (0xdc00u + (codepoint & 0x3ffu));
        }
    }

    jstring result = length == 0 ? NULL : (*env)->NewString(env, chars, (jsize) length);
    free(chars);
    return result;
}

JNIEXPORT jintArray JNICALL Java_com_termux_terminal_JNI_ghosttySnapshotCells(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jint columns,
        jint rows)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL || bridge->render_state == NULL || columns <= 0 || rows <= 0) return NULL;

    GhosttyResult result = gRenderStateUpdate(bridge->render_state, bridge->terminal);
    if (result != 0) {
        throw_runtime_exception(env, "Failed to update libghostty-vt render state");
        return NULL;
    }

    size_t cellCount = (size_t) columns * (size_t) rows;
    size_t cellItemCount = cellCount * TERMUX_RENDER_CELL_STRIDE;
    size_t metadataCount = 1u + (size_t) rows;
    size_t itemCount = cellItemCount + metadataCount;
    jintArray resultArray = (*env)->NewIntArray(env, (jsize) itemCount);
    if (resultArray == NULL) return NULL;

    jint* snapshot = (jint*) calloc(itemCount, sizeof(jint));
    if (snapshot == NULL) {
        throw_runtime_exception(env, "Failed to allocate libghostty-vt render snapshot");
        return NULL;
    }

    jint dirtyState = 2;
    result = gRenderStateGet(bridge->render_state, 3, &dirtyState);
    if (result != 0)
        dirtyState = 2;
    snapshot[cellItemCount] = dirtyState;

    GhosttyRenderStateRowIterator rowIterator = NULL;
    result = gRenderStateRowIteratorNew(NULL, &rowIterator);
    if (result != 0 || rowIterator == NULL) {
        free(snapshot);
        throw_runtime_exception(env, "Failed to allocate libghostty-vt row iterator");
        return NULL;
    }

    result = gRenderStateGet(bridge->render_state, 4, &rowIterator);
    if (result != 0) {
        gRenderStateRowIteratorFree(rowIterator);
        free(snapshot);
        throw_runtime_exception(env, "Failed to read libghostty-vt rows");
        return NULL;
    }

    GhosttyRenderStateRowCells cells = NULL;
    result = gRenderStateRowCellsNew(NULL, &cells);
    if (result != 0 || cells == NULL) {
        gRenderStateRowIteratorFree(rowIterator);
        free(snapshot);
        throw_runtime_exception(env, "Failed to allocate libghostty-vt cell iterator");
        return NULL;
    }

    jint row = 0;
    while (row < rows && gRenderStateRowIteratorNext(rowIterator)) {
        bool rowDirty = dirtyState == 2;
        if (dirtyState != 2) {
            bool queriedRowDirty = false;
            if (gRenderStateRowGet(rowIterator, 1, &queriedRowDirty) == 0)
                rowDirty = queriedRowDirty;
        }
        snapshot[cellItemCount + 1u + (size_t) row] = rowDirty ? 1 : 0;

        result = gRenderStateRowGet(rowIterator, 3, &cells);
        if (result != 0) {
            row++;
            continue;
        }

        jint column = 0;
        while (column < columns && gRenderStateRowCellsNext(cells)) {
            size_t base = ((size_t) row * (size_t) columns + (size_t) column) * TERMUX_RENDER_CELL_STRIDE;

            GhosttyCell rawCell = 0;
            bool hasRawCell = gRenderStateRowCellsGet(cells, 1, &rawCell) == 0;
            if (hasRawCell)
                snapshot[base + 4] = termux_width_from_ghostty_cell(rawCell);
            else
                snapshot[base + 4] = 1;
            bool selected = false;
            if (gRenderStateRowCellsGet(cells, GHOSTTY_RENDER_CELL_SELECTED, &selected) == 0)
                snapshot[base + 5] = selected ? 1 : 0;

            GhosttyStyle style = { .size = sizeof(GhosttyStyle) };
            result = gRenderStateRowCellsGet(cells, 2, &style);
            if (result == 0) {
                jint fg_color = termux_color_from_ghostty_color(style.fg_color, 256);
                jint bg_color = termux_color_from_ghostty_color(style.bg_color, 257);
                GhosttyColorRgb resolved_color = {0};
                if (gRenderStateRowCellsGet(cells, 6, &resolved_color) == 0)
                    fg_color = argb_from_ghostty_rgb(resolved_color);
                if (gRenderStateRowCellsGet(cells, 5, &resolved_color) == 0)
                    bg_color = argb_from_ghostty_rgb(resolved_color);
                snapshot[base + 1] = fg_color;
                snapshot[base + 2] = bg_color;
                jint effect = termux_effect_from_ghostty_style(&style);
                if (hasRawCell)
                    effect |= termux_protected_effect_from_ghostty_cell(rawCell);
                snapshot[base + 3] = effect;
                snapshot[base + 6] = termux_color_from_ghostty_color(style.underline_color, TERMUX_RENDER_CELL_COLOR_DEFAULT);
                snapshot[base + 7] = (jint) style.underline;
                snapshot[base + 8] = style.overline ? 1 : 0;
            } else {
                snapshot[base + 1] = 256;
                snapshot[base + 2] = 257;
                snapshot[base + 3] = 0;
                snapshot[base + 6] = TERMUX_RENDER_CELL_COLOR_DEFAULT;
                snapshot[base + 7] = 0;
                snapshot[base + 8] = 0;
            }

            uint32_t graphemeLen = 0;
            result = gRenderStateRowCellsGet(cells, 3, &graphemeLen);
            if (result == 0 && graphemeLen > 0) {
                uint32_t stackGraphemes[32] = {0};
                uint32_t* graphemes = stackGraphemes;
                if (graphemeLen > 32) {
                    graphemes = (uint32_t*) calloc((size_t) graphemeLen, sizeof(uint32_t));
                }
                if (graphemes != NULL) {
                    result = gRenderStateRowCellsGet(cells, 4, graphemes);
                    if (result == 0)
                        snapshot[base] = (jint) graphemes[0];
                    if (graphemes != stackGraphemes)
                        free(graphemes);
                }
            }

            column++;
        }
        row++;
    }

    gRenderStateRowCellsFree(cells);
    gRenderStateRowIteratorFree(rowIterator);

    (*env)->SetIntArrayRegion(env, resultArray, 0, (jsize) itemCount, snapshot);
    free(snapshot);
    return resultArray;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_ghosttyClearRenderDirtyState(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL || bridge->render_state == NULL) return;

    GhosttyRenderStateRowIterator rowIterator = NULL;
    GhosttyResult result = gRenderStateRowIteratorNew(NULL, &rowIterator);
    if (result != 0 || rowIterator == NULL) {
        throw_runtime_exception(env, "Failed to allocate libghostty-vt dirty row iterator");
        return;
    }

    result = gRenderStateGet(bridge->render_state, 4, &rowIterator);
    if (result != 0) {
        gRenderStateRowIteratorFree(rowIterator);
        throw_runtime_exception(env, "Failed to read libghostty-vt dirty rows");
        return;
    }

    bool cleanRow = false;
    while (gRenderStateRowIteratorNext(rowIterator))
        gRenderStateRowSet(rowIterator, 0, &cleanRow);
    gRenderStateRowIteratorFree(rowIterator);

    jint cleanDirty = 0;
    gRenderStateSet(bridge->render_state, 0, &cleanDirty);
}

static jobject create_kitty_graphics_placement(JNIEnv* env, GhosttyBridgeContext* bridge,
                                               GhosttyKittyGraphics graphics,
                                               GhosttyKittyGraphicsPlacementIterator iterator,
                                               jclass placement_class, jmethodID placement_constructor) {
    uint32_t image_id = 0;
    if (gKittyGraphicsPlacementGet(iterator, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IMAGE_ID, &image_id) != 0)
        return NULL;

    bool is_virtual = false;
    if (gKittyGraphicsPlacementGet(iterator, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IS_VIRTUAL, &is_virtual) == 0 &&
        is_virtual) {
        return NULL;
    }

    GhosttyKittyGraphicsImage image = gKittyGraphicsImage(graphics, image_id);
    if (image == NULL)
        return NULL;

    GhosttyKittyGraphicsPlacementRenderInfo render_info = {
        .size = sizeof(GhosttyKittyGraphicsPlacementRenderInfo),
    };
    if (gKittyGraphicsPlacementRenderInfo(iterator, image, bridge->terminal, &render_info) != 0 ||
        !render_info.viewport_visible) {
        return NULL;
    }

    uint32_t placement_id = 0;
    uint32_t x_offset = 0;
    uint32_t y_offset = 0;
    int32_t z_index = 0;
    gKittyGraphicsPlacementGet(iterator, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_PLACEMENT_ID, &placement_id);
    gKittyGraphicsPlacementGet(iterator, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_X_OFFSET, &x_offset);
    gKittyGraphicsPlacementGet(iterator, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Y_OFFSET, &y_offset);
    gKittyGraphicsPlacementGet(iterator, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Z, &z_index);

    uint32_t image_width = 0;
    uint32_t image_height = 0;
    int image_format = 0;
    int image_compression = 0;
    const uint8_t* data_ptr = NULL;
    size_t data_len = 0;
    gKittyGraphicsImageGet(image, GHOSTTY_KITTY_IMAGE_DATA_WIDTH, &image_width);
    gKittyGraphicsImageGet(image, GHOSTTY_KITTY_IMAGE_DATA_HEIGHT, &image_height);
    gKittyGraphicsImageGet(image, GHOSTTY_KITTY_IMAGE_DATA_FORMAT, &image_format);
    gKittyGraphicsImageGet(image, GHOSTTY_KITTY_IMAGE_DATA_COMPRESSION, &image_compression);
    gKittyGraphicsImageGet(image, GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR, &data_ptr);
    gKittyGraphicsImageGet(image, GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN, &data_len);
    if (data_len > (size_t) INT32_MAX)
        return NULL;

    jbyteArray data = (*env)->NewByteArray(env, (jsize) data_len);
    if (data == NULL)
        return NULL;
    if (data_ptr != NULL && data_len > 0)
        (*env)->SetByteArrayRegion(env, data, 0, (jsize) data_len, (const jbyte*) data_ptr);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->DeleteLocalRef(env, data);
        return NULL;
    }

    jobject placement = (*env)->NewObject(env, placement_class, placement_constructor,
        (jint) image_id,
        (jint) placement_id,
        (jint) z_index,
        (jint) x_offset,
        (jint) y_offset,
        (jint) image_width,
        (jint) image_height,
        (jint) image_format,
        (jint) image_compression,
        (jint) render_info.pixel_width,
        (jint) render_info.pixel_height,
        (jint) render_info.grid_cols,
        (jint) render_info.grid_rows,
        (jint) render_info.viewport_col,
        (jint) render_info.viewport_row,
        (jint) render_info.source_x,
        (jint) render_info.source_y,
        (jint) render_info.source_width,
        (jint) render_info.source_height,
        data);
    (*env)->DeleteLocalRef(env, data);
    return placement;
}

JNIEXPORT jobjectArray JNICALL Java_com_termux_terminal_JNI_ghosttySnapshotKittyGraphicsPlacements(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return NULL;

    jclass placement_class = (*env)->FindClass(env, "com/termux/terminal/TerminalKittyGraphicsPlacement");
    if (placement_class == NULL)
        return NULL;
    jmethodID placement_constructor = (*env)->GetMethodID(env, placement_class, "<init>",
        "(IIIIIIIIIIIIIIIIIII[B)V");
    if (placement_constructor == NULL)
        return NULL;

    GhosttyKittyGraphics graphics = NULL;
    GhosttyResult result = gTerminalGet(bridge->terminal, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS, &graphics);
    if (result == GHOSTTY_NO_VALUE || graphics == NULL)
        return (*env)->NewObjectArray(env, 0, placement_class, NULL);
    if (result != 0) {
        throw_runtime_exception(env, "Failed to read libghostty-vt Kitty graphics storage");
        return NULL;
    }

    GhosttyKittyGraphicsPlacementIterator iterator = NULL;
    result = gKittyGraphicsPlacementIteratorNew(NULL, &iterator);
    if (result != 0 || iterator == NULL) {
        throw_runtime_exception(env, "Failed to allocate libghostty-vt Kitty placement iterator");
        return NULL;
    }

    result = gKittyGraphicsGet(graphics, GHOSTTY_KITTY_GRAPHICS_DATA_PLACEMENT_ITERATOR, &iterator);
    if (result == GHOSTTY_NO_VALUE) {
        gKittyGraphicsPlacementIteratorFree(iterator);
        return (*env)->NewObjectArray(env, 0, placement_class, NULL);
    }
    if (result != 0) {
        gKittyGraphicsPlacementIteratorFree(iterator);
        throw_runtime_exception(env, "Failed to read libghostty-vt Kitty placements");
        return NULL;
    }

    // Build directly into a Java object array, releasing each placement's local
    // reference as soon as it is stored in the array. The previous approach
    // accumulated one live local ref per placement in a C array until the whole
    // loop finished, overflowing the JNI local reference table (default 512 on
    // ART through Android 13) when a screen held hundreds of visible Kitty
    // placements — aborting the process on every syncScreenSnapshot.
    jsize capacity = 8;
    jsize count = 0;
    jobjectArray result_array = (*env)->NewObjectArray(env, capacity, placement_class, NULL);
    if (result_array == NULL) {
        gKittyGraphicsPlacementIteratorFree(iterator);
        return NULL;
    }

    while (gKittyGraphicsPlacementNext(iterator)) {
        jobject placement = create_kitty_graphics_placement(env, bridge, graphics, iterator,
            placement_class, placement_constructor);
        if ((*env)->ExceptionCheck(env))
            break;
        if (placement == NULL)
            continue;
        if (count == capacity) {
            jsize new_capacity = capacity * 2;
            jobjectArray grown = (*env)->NewObjectArray(env, new_capacity, placement_class, NULL);
            if (grown == NULL) {
                (*env)->DeleteLocalRef(env, placement);
                break;
            }
            // Move existing elements into the larger array; each fetched local
            // ref is deleted immediately so at most one extra local lives here.
            for (jsize i = 0; i < count; i++) {
                jobject existing = (*env)->GetObjectArrayElement(env, result_array, i);
                (*env)->SetObjectArrayElement(env, grown, i, existing);
                (*env)->DeleteLocalRef(env, existing);
            }
            (*env)->DeleteLocalRef(env, result_array);
            result_array = grown;
            capacity = new_capacity;
        }
        (*env)->SetObjectArrayElement(env, result_array, count, placement);
        (*env)->DeleteLocalRef(env, placement);
        count++;
    }

    gKittyGraphicsPlacementIteratorFree(iterator);

    if ((*env)->ExceptionCheck(env))
        return NULL;

    // Trim to the exact count so callers see no trailing nulls.
    if (count != capacity) {
        jobjectArray trimmed = (*env)->NewObjectArray(env, count, placement_class, NULL);
        if (trimmed == NULL)
            return NULL;
        for (jsize i = 0; i < count; i++) {
            jobject existing = (*env)->GetObjectArrayElement(env, result_array, i);
            (*env)->SetObjectArrayElement(env, trimmed, i, existing);
            (*env)->DeleteLocalRef(env, existing);
        }
        (*env)->DeleteLocalRef(env, result_array);
        result_array = trimmed;
    }
    return result_array;
}

JNIEXPORT jobjectArray JNICALL Java_com_termux_terminal_JNI_ghosttySnapshotCellText(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jint columns,
        jint rows)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL || bridge->render_state == NULL || columns <= 0 || rows <= 0) return NULL;

    GhosttyRenderStateRowIterator rowIterator = NULL;
    GhosttyResult result = gRenderStateRowIteratorNew(NULL, &rowIterator);
    if (result != 0 || rowIterator == NULL) {
        throw_runtime_exception(env, "Failed to allocate libghostty-vt grapheme row iterator");
        return NULL;
    }

    result = gRenderStateGet(bridge->render_state, 4, &rowIterator);
    if (result != 0) {
        gRenderStateRowIteratorFree(rowIterator);
        throw_runtime_exception(env, "Failed to read libghostty-vt grapheme rows");
        return NULL;
    }

    GhosttyRenderStateRowCells cells = NULL;
    result = gRenderStateRowCellsNew(NULL, &cells);
    if (result != 0 || cells == NULL) {
        gRenderStateRowIteratorFree(rowIterator);
        throw_runtime_exception(env, "Failed to allocate libghostty-vt grapheme cell iterator");
        return NULL;
    }

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (stringClass == NULL) {
        gRenderStateRowCellsFree(cells);
        gRenderStateRowIteratorFree(rowIterator);
        return NULL;
    }

    jobjectArray resultArray = NULL;
    jint row = 0;
    while (row < rows && gRenderStateRowIteratorNext(rowIterator)) {
        result = gRenderStateRowGet(rowIterator, 3, &cells);
        if (result != 0) {
            row++;
            continue;
        }

        jint column = 0;
        while (column < columns && gRenderStateRowCellsNext(cells)) {
            uint32_t graphemeLen = 0;
            result = gRenderStateRowCellsGet(cells, 3, &graphemeLen);
            if (result == 0 && graphemeLen > 1) {
                uint32_t stackGraphemes[32] = {0};
                uint32_t* graphemes = stackGraphemes;
                if (graphemeLen > 32)
                    graphemes = (uint32_t*) calloc((size_t) graphemeLen, sizeof(uint32_t));
                if (graphemes != NULL) {
                    result = gRenderStateRowCellsGet(cells, 4, graphemes);
                    if (result == 0) {
                        jstring text = jstring_from_codepoints(env, graphemes, graphemeLen);
                        if ((*env)->ExceptionCheck(env)) {
                            if (graphemes != stackGraphemes)
                                free(graphemes);
                            gRenderStateRowCellsFree(cells);
                            gRenderStateRowIteratorFree(rowIterator);
                            return NULL;
                        }
                        if (text != NULL) {
                            if (resultArray == NULL) {
                                resultArray = (*env)->NewObjectArray(env, (jsize) ((size_t) columns * (size_t) rows), stringClass, NULL);
                                if (resultArray == NULL) {
                                    if (graphemes != stackGraphemes)
                                        free(graphemes);
                                    gRenderStateRowCellsFree(cells);
                                    gRenderStateRowIteratorFree(rowIterator);
                                    return NULL;
                                }
                            }
                            (*env)->SetObjectArrayElement(env, resultArray, row * columns + column, text);
                            (*env)->DeleteLocalRef(env, text);
                        }
                    }
                    if (graphemes != stackGraphemes)
                        free(graphemes);
                }
            }
            column++;
        }
        row++;
    }

    gRenderStateRowCellsFree(cells);
    gRenderStateRowIteratorFree(rowIterator);
    return resultArray;
}

JNIEXPORT jintArray JNICALL Java_com_termux_terminal_JNI_ghosttySnapshotCursor(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL || bridge->render_state == NULL) return NULL;

    bool hasValue = false;
    bool visible = false;
    bool wideTail = false;
    bool blinking = true;
    bool passwordInput = false;
    uint16_t x = 0;
    uint16_t y = 0;
    int style = 1;

    gRenderStateGet(bridge->render_state, 14, &hasValue);
    if (hasValue) {
        gRenderStateGet(bridge->render_state, 15, &x);
        gRenderStateGet(bridge->render_state, 16, &y);
        gRenderStateGet(bridge->render_state, 17, &wideTail);
    }
    gRenderStateGet(bridge->render_state, 10, &style);
    gRenderStateGet(bridge->render_state, 11, &visible);
    gRenderStateGet(bridge->render_state, 12, &blinking);
    gRenderStateGet(bridge->render_state, 13, &passwordInput);

    jint values[8] = {
        hasValue ? 1 : 0,
        (jint) x,
        (jint) y,
        style,
        visible ? 1 : 0,
        wideTail ? 1 : 0,
        blinking ? 1 : 0,
        passwordInput ? 1 : 0,
    };
    jintArray resultArray = (*env)->NewIntArray(env, 8);
    if (resultArray == NULL) return NULL;
    (*env)->SetIntArrayRegion(env, resultArray, 0, 8, values);
    return resultArray;
}

JNIEXPORT jintArray JNICALL Java_com_termux_terminal_JNI_ghosttySnapshotColors(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL || bridge->render_state == NULL) return NULL;

    jintArray resultArray = (*env)->NewIntArray(env, 259);
    if (resultArray == NULL) return NULL;

    GhosttyRenderStateColors colors = { .size = sizeof(GhosttyRenderStateColors) };
    GhosttyResult result = gRenderStateColorsGet(bridge->render_state, &colors);
    if (result != 0) {
        throw_runtime_exception(env, "Failed to read libghostty-vt render colors");
        return NULL;
    }

    jint resultColors[259] = {0};
    for (size_t i = 0; i < 256; i++)
        resultColors[i] = argb_from_ghostty_rgb(colors.palette[i]);
    resultColors[256] = argb_from_ghostty_rgb(colors.foreground);
    resultColors[257] = argb_from_ghostty_rgb(colors.background);
    resultColors[258] = colors.cursor_has_value ? argb_from_ghostty_rgb(colors.cursor) : resultColors[256];

    (*env)->SetIntArrayRegion(env, resultArray, 0, 259, resultColors);
    return resultArray;
}

JNIEXPORT jboolean JNICALL Java_com_termux_terminal_JNI_ghosttyApplyDefaultColors(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jintArray colorsArray)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL || colorsArray == NULL || gTerminalSet == NULL) return JNI_FALSE;

    jsize colorCount = (*env)->GetArrayLength(env, colorsArray);
    if (colorCount < 259) return JNI_FALSE;

    jint colors[259] = {0};
    (*env)->GetIntArrayRegion(env, colorsArray, 0, 259, colors);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;

    GhosttyColorRgb palette[256];
    for (size_t i = 0; i < 256; i++)
        palette[i] = ghostty_rgb_from_argb(colors[i]);

    GhosttyColorRgb foreground = ghostty_rgb_from_argb(colors[256]);
    GhosttyColorRgb background = ghostty_rgb_from_argb(colors[257]);
    GhosttyColorRgb cursor = ghostty_rgb_from_argb(colors[258]);

    if (gTerminalSet(bridge->terminal, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, &palette) != 0 ||
        gTerminalSet(bridge->terminal, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &foreground) != 0 ||
        gTerminalSet(bridge->terminal, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &background) != 0 ||
        gTerminalSet(bridge->terminal, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &cursor) != 0) {
        return JNI_FALSE;
    }

    static const uint8_t resetDynamicColors[] = "\033]104\007\033]110\007\033]111\007\033]112\007";
    gTerminalVtWrite(bridge->terminal, resetDynamicColors, sizeof(resetDynamicColors) - 1u);
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL Java_com_termux_terminal_JNI_ghosttyEncodeKey(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jint keyCode,
        jint keyMod)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return NULL;

    GhosttyKey key = GHOSTTY_KEY_UNIDENTIFIED;
    if (!ghostty_key_from_android(keyCode, keyMod, &key))
        return NULL;

    GhosttyKeyEncoder encoder = NULL;
    GhosttyResult result = gKeyEncoderNew(NULL, &encoder);
    if (result != 0 || encoder == NULL) {
        throw_runtime_exception(env, "Failed to create libghostty-vt key encoder");
        return NULL;
    }
    gKeyEncoderSetoptFromTerminal(encoder, bridge->terminal);

    GhosttyKeyEvent event = NULL;
    result = gKeyEventNew(NULL, &event);
    if (result != 0 || event == NULL) {
        gKeyEncoderFree(encoder);
        throw_runtime_exception(env, "Failed to create libghostty-vt key event");
        return NULL;
    }
    GhosttyMods mods = key_mods_from_termux(keyMod);
    gKeyEventSetAction(event, GHOSTTY_KEY_ACTION_PRESS);
    gKeyEventSetKey(event, key);
    gKeyEventSetMods(event, mods);

    // Mirror the codepoint encoder: feed utf8 for keys with a natural ASCII
    // representation so the encoder can apply modifier transformations
    // (e.g. Ctrl-Space → NUL). Without utf8 the legacy encoder path emits
    // nothing for "text-like" keys held with Ctrl, swallowing user input.
    const char* key_utf8 = NULL;
    size_t key_utf8_len = 0;
    switch (key) {
        case GHOSTTY_KEY_SPACE: key_utf8 = " "; key_utf8_len = 1; break;
        case GHOSTTY_KEY_TAB:   key_utf8 = "\t"; key_utf8_len = 1; break;
        case GHOSTTY_KEY_ENTER: key_utf8 = "\r"; key_utf8_len = 1; break;
        default: break;
    }
    if (key_utf8_len > 0)
        gKeyEventSetUtf8(event, key_utf8, key_utf8_len);

    char stackBuffer[128];
    size_t written = 0;
    result = gKeyEncoderEncode(encoder, event, stackBuffer, sizeof(stackBuffer), &written);
    char* output = stackBuffer;
    if (result == GHOSTTY_OUT_OF_SPACE && written > sizeof(stackBuffer)) {
        output = (char*) malloc(written);
        if (output == NULL) {
            gKeyEventFree(event);
            gKeyEncoderFree(encoder);
            throw_runtime_exception(env, "Failed to allocate libghostty-vt key buffer");
            return NULL;
        }
        result = gKeyEncoderEncode(encoder, event, output, written, &written);
    }

    jbyteArray resultArray = NULL;
    if (result == 0 && written > 0) {
        resultArray = (*env)->NewByteArray(env, (jsize) written);
        if (resultArray != NULL)
            (*env)->SetByteArrayRegion(env, resultArray, 0, (jsize) written, (const jbyte*) output);
    }

    if (output != stackBuffer)
        free(output);
    gKeyEventFree(event);
    gKeyEncoderFree(encoder);

    if (result != 0 && result != GHOSTTY_OUT_OF_SPACE) {
        throw_runtime_exception(env, "Failed to encode libghostty-vt key event");
        return NULL;
    }

    // Safety net: if the encoder produced no output for a Ctrl-modified key
    // that has a well-defined C0 control byte, emit the byte directly. This
    // catches encoder builds that don't apply Ctrl transformations to keyed
    // (non-codepoint) inputs.
    if (resultArray == NULL && (mods & GHOSTTY_MODS_CTRL) != 0) {
        jbyte fallback = -1;
        switch (key) {
            case GHOSTTY_KEY_SPACE: fallback = (jbyte) 0x00; break;
            default: break;
        }
        if (fallback != (jbyte) -1) {
            jsize n = (mods & GHOSTTY_MODS_ALT) != 0 ? 2 : 1;
            resultArray = (*env)->NewByteArray(env, n);
            if (resultArray != NULL) {
                jbyte buf[2];
                jsize idx = 0;
                if ((mods & GHOSTTY_MODS_ALT) != 0) buf[idx++] = (jbyte) 0x1b;
                buf[idx++] = fallback;
                (*env)->SetByteArrayRegion(env, resultArray, 0, n, buf);
            }
        }
    }
    return resultArray;
}

// Safety net for Ctrl+letter: emit the legacy C0 control byte (a→0x01 .. z→0x1a)
// when the Ghostty key encoder declines to produce anything. Returns NULL when
// the input is not a Ctrl+lowercase-letter combo. Prepends ESC for Alt
// (Meta-prefix convention).
static jbyteArray ctrl_letter_c0_fallback(JNIEnv* env, jint codepoint, jboolean controlDown, jboolean altDown)
{
    if (controlDown != JNI_TRUE || codepoint < 'a' || codepoint > 'z')
        return NULL;
    jsize n = (altDown == JNI_TRUE) ? 2 : 1;
    jbyteArray arr = (*env)->NewByteArray(env, n);
    if (arr == NULL) return NULL;
    jbyte buf[2];
    jsize idx = 0;
    if (altDown == JNI_TRUE) buf[idx++] = (jbyte) 0x1b;
    buf[idx++] = (jbyte) (codepoint - 'a' + 1);
    (*env)->SetByteArrayRegion(env, arr, 0, n, buf);
    return arr;
}

JNIEXPORT jbyteArray JNICALL Java_com_termux_terminal_JNI_ghosttyEncodeCodePoint(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jint codepoint,
        jboolean controlDown,
        jboolean altDown)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return NULL;

    // TerminalView.inputCodePoint() pre-maps the special Ctrl combos that
    // libghostty-vt's encoder does not cover (Ctrl-Space→NUL, Ctrl-2..8,
    // Ctrl-[/\/]/^/_, Ctrl-/) straight to their C0 byte. Routing those raw C0
    // bytes back through the encoder drops them (it rejects raw C0), so emit the
    // byte directly; prepend ESC if Alt was also held (Meta-prefix convention).
    // Ctrl+letter is NOT pre-mapped anymore — it arrives as the bare letter +
    // controlDown and goes through the encoder below so the kitty keyboard
    // protocol / modifyOtherKeys can produce CSI-u, with a C0 fallback.
    if ((codepoint >= 0 && codepoint <= 0x1f) || codepoint == 0x7f) {
        jsize n = (altDown == JNI_TRUE) ? 2 : 1;
        jbyteArray arr = (*env)->NewByteArray(env, n);
        if (arr == NULL) return NULL;
        jbyte buf[2];
        jsize idx = 0;
        if (altDown == JNI_TRUE) buf[idx++] = (jbyte) 0x1b;
        buf[idx++] = (jbyte) codepoint;
        (*env)->SetByteArrayRegion(env, arr, 0, n, buf);
        return arr;
    }

    GhosttyKey key = GHOSTTY_KEY_UNIDENTIFIED;
    if (!ghostty_key_from_codepoint(codepoint, &key))
        return ctrl_letter_c0_fallback(env, codepoint, controlDown, altDown);

    char utf8[4];
    size_t utf8_len = 0;
    if (!utf8_from_codepoint(codepoint, utf8, &utf8_len)) {
        throw_runtime_exception(env, "Invalid Unicode code point for libghostty-vt key encoder");
        return NULL;
    }
    if (key == GHOSTTY_KEY_UNIDENTIFIED && utf8_len == 0)
        return NULL;

    GhosttyKeyEncoder encoder = NULL;
    GhosttyResult result = gKeyEncoderNew(NULL, &encoder);
    if (result != 0 || encoder == NULL) {
        throw_runtime_exception(env, "Failed to create libghostty-vt key encoder");
        return NULL;
    }
    gKeyEncoderSetoptFromTerminal(encoder, bridge->terminal);

    GhosttyKeyEvent event = NULL;
    result = gKeyEventNew(NULL, &event);
    if (result != 0 || event == NULL) {
        gKeyEncoderFree(encoder);
        throw_runtime_exception(env, "Failed to create libghostty-vt key event");
        return NULL;
    }
    GhosttyMods mods = 0;
    if (controlDown == JNI_TRUE) mods |= GHOSTTY_MODS_CTRL;
    if (altDown == JNI_TRUE) mods |= GHOSTTY_MODS_ALT;
    gKeyEventSetAction(event, GHOSTTY_KEY_ACTION_PRESS);
    gKeyEventSetKey(event, key);
    gKeyEventSetMods(event, mods);
    if (utf8_len > 0)
        gKeyEventSetUtf8(event, utf8, utf8_len);

    char stackBuffer[128];
    size_t written = 0;
    result = gKeyEncoderEncode(encoder, event, stackBuffer, sizeof(stackBuffer), &written);
    char* output = stackBuffer;
    if (result == GHOSTTY_OUT_OF_SPACE && written > sizeof(stackBuffer)) {
        output = (char*) malloc(written);
        if (output == NULL) {
            gKeyEventFree(event);
            gKeyEncoderFree(encoder);
            throw_runtime_exception(env, "Failed to allocate libghostty-vt text input buffer");
            return NULL;
        }
        result = gKeyEncoderEncode(encoder, event, output, written, &written);
    }

    jbyteArray resultArray = NULL;
    if (result == 0 && written > 0) {
        resultArray = (*env)->NewByteArray(env, (jsize) written);
        if (resultArray != NULL)
            (*env)->SetByteArrayRegion(env, resultArray, 0, (jsize) written, (const jbyte*) output);
    }

    if (output != stackBuffer)
        free(output);
    gKeyEventFree(event);
    gKeyEncoderFree(encoder);

    if (result != 0 && result != GHOSTTY_OUT_OF_SPACE) {
        throw_runtime_exception(env, "Failed to encode libghostty-vt text input");
        return NULL;
    }
    // Encoder produced nothing (e.g. a build/mode where Ctrl+letter isn't
    // encoded): fall back to the legacy C0 byte so the key is never dropped.
    if (resultArray == NULL)
        resultArray = ctrl_letter_c0_fallback(env, codepoint, controlDown, altDown);
    return resultArray;
}

JNIEXPORT jbyteArray JNICALL Java_com_termux_terminal_JNI_ghosttyEncodeMouse(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jint mouseButton,
        jint column,
        jint row,
        jboolean pressed,
        jint columns,
        jint rows,
        jint cellWidthPixels,
        jint cellHeightPixels)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return NULL;

    int action = GHOSTTY_MOUSE_ACTION_PRESS;
    int button = GHOSTTY_MOUSE_BUTTON_LEFT;
    if (!ghostty_mouse_from_termux(mouseButton, pressed, &action, &button))
        return NULL;

    if (columns <= 0 || rows <= 0 || cellWidthPixels <= 0 || cellHeightPixels <= 0)
        return NULL;
    if (column < 1) column = 1;
    if (column > columns) column = columns;
    if (row < 1) row = 1;
    if (row > rows) row = rows;

    GhosttyMouseEncoder encoder = NULL;
    GhosttyResult result = gMouseEncoderNew(NULL, &encoder);
    if (result != 0 || encoder == NULL) {
        throw_runtime_exception(env, "Failed to create libghostty-vt mouse encoder");
        return NULL;
    }
    gMouseEncoderSetoptFromTerminal(encoder, bridge->terminal);

    GhosttyMouseEncoderSize size = {
        .size = sizeof(GhosttyMouseEncoderSize),
        .screen_width = (uint32_t) (columns * cellWidthPixels),
        .screen_height = (uint32_t) (rows * cellHeightPixels),
        .cell_width = (uint32_t) cellWidthPixels,
        .cell_height = (uint32_t) cellHeightPixels,
        .padding_top = 0,
        .padding_bottom = 0,
        .padding_right = 0,
        .padding_left = 0,
    };
    gMouseEncoderSetopt(encoder, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &size);

    GhosttyMouseEvent event = NULL;
    result = gMouseEventNew(NULL, &event);
    if (result != 0 || event == NULL) {
        gMouseEncoderFree(encoder);
        throw_runtime_exception(env, "Failed to create libghostty-vt mouse event");
        return NULL;
    }
    gMouseEventSetAction(event, action);
    gMouseEventSetButton(event, button);
    GhosttyMousePosition position = {
        .x = ((float) column - 0.5f) * (float) cellWidthPixels,
        .y = ((float) row - 0.5f) * (float) cellHeightPixels,
    };
    gMouseEventSetPosition(event, position);

    char stackBuffer[128];
    size_t written = 0;
    result = gMouseEncoderEncode(encoder, event, stackBuffer, sizeof(stackBuffer), &written);
    char* output = stackBuffer;
    if (result == GHOSTTY_OUT_OF_SPACE && written > sizeof(stackBuffer)) {
        output = (char*) malloc(written);
        if (output == NULL) {
            gMouseEventFree(event);
            gMouseEncoderFree(encoder);
            throw_runtime_exception(env, "Failed to allocate libghostty-vt mouse buffer");
            return NULL;
        }
        result = gMouseEncoderEncode(encoder, event, output, written, &written);
    }

    jbyteArray resultArray = NULL;
    if (result == 0 && written > 0) {
        resultArray = (*env)->NewByteArray(env, (jsize) written);
        if (resultArray != NULL)
            (*env)->SetByteArrayRegion(env, resultArray, 0, (jsize) written, (const jbyte*) output);
    }

    if (output != stackBuffer)
        free(output);
    gMouseEventFree(event);
    gMouseEncoderFree(encoder);

    if (result != 0 && result != GHOSTTY_OUT_OF_SPACE) {
        throw_runtime_exception(env, "Failed to encode libghostty-vt mouse event");
        return NULL;
    }
    return resultArray;
}

JNIEXPORT jbyteArray JNICALL Java_com_termux_terminal_JNI_ghosttyEncodeFocus(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jboolean focused)
{
    if (!load_ghostty_symbols() || gFocusEncode == NULL)
        return NULL;

    char stackBuffer[8];
    size_t written = 0;
    GhosttyResult result = gFocusEncode(focused ? GHOSTTY_FOCUS_GAINED : GHOSTTY_FOCUS_LOST,
        stackBuffer, sizeof(stackBuffer), &written);
    char* output = stackBuffer;
    if (result == GHOSTTY_OUT_OF_SPACE && written > sizeof(stackBuffer)) {
        output = (char*) malloc(written);
        if (output == NULL) {
            throw_runtime_exception(env, "Failed to allocate libghostty-vt focus buffer");
            return NULL;
        }
        result = gFocusEncode(focused ? GHOSTTY_FOCUS_GAINED : GHOSTTY_FOCUS_LOST,
            output, written, &written);
    }

    jbyteArray resultArray = NULL;
    if (result == 0 && written > 0) {
        resultArray = (*env)->NewByteArray(env, (jsize) written);
        if (resultArray != NULL)
            (*env)->SetByteArrayRegion(env, resultArray, 0, (jsize) written, (const jbyte*) output);
    }

    if (output != stackBuffer)
        free(output);
    if (result != 0 && result != GHOSTTY_OUT_OF_SPACE) {
        throw_runtime_exception(env, "Failed to encode libghostty-vt focus event");
        return NULL;
    }
    return resultArray;
}

JNIEXPORT jbyteArray JNICALL Java_com_termux_terminal_JNI_ghosttyEncodePaste(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jbyteArray dataArray)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL || dataArray == NULL || gPasteEncode == NULL) return NULL;

    jsize dataLen = (*env)->GetArrayLength(env, dataArray);
    if (dataLen <= 0)
        return NULL;

    size_t dataLenNative = (size_t) dataLen;
    char* data = (char*) malloc(dataLenNative);
    if (data == NULL) {
        throw_runtime_exception(env, "Failed to allocate libghostty-vt paste input buffer");
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, dataArray, 0, dataLen, (jbyte*) data);
    if ((*env)->ExceptionCheck(env)) {
        free(data);
        return NULL;
    }

    bool bracketed = false;
    if (gTerminalModeGet != NULL) {
        bool modeActive = false;
        if (gTerminalModeGet(bridge->terminal, GHOSTTY_MODE_BRACKETED_PASTE, &modeActive) == 0)
            bracketed = modeActive;
    }

    size_t outputCap = dataLenNative + 32u;
    if (outputCap < dataLenNative) {
        free(data);
        throw_runtime_exception(env, "Paste data is too large to encode");
        return NULL;
    }
    char* output = (char*) malloc(outputCap);
    if (output == NULL) {
        free(data);
        throw_runtime_exception(env, "Failed to allocate libghostty-vt paste output buffer");
        return NULL;
    }

    size_t written = 0;
    GhosttyResult result = gPasteEncode(data, dataLenNative, bracketed, output, outputCap, &written);
    if (result == GHOSTTY_OUT_OF_SPACE && written > outputCap) {
        char* resized = (char*) realloc(output, written);
        if (resized == NULL) {
            free(output);
            free(data);
            throw_runtime_exception(env, "Failed to grow libghostty-vt paste output buffer");
            return NULL;
        }
        output = resized;
        outputCap = written;
        result = gPasteEncode(data, dataLenNative, bracketed, output, outputCap, &written);
    }

    jbyteArray resultArray = NULL;
    if (result == 0 && written > 0) {
        if (written > (size_t) INT32_MAX) {
            free(output);
            free(data);
            throw_runtime_exception(env, "Encoded paste data exceeds JNI array limits");
            return NULL;
        }
        resultArray = (*env)->NewByteArray(env, (jsize) written);
        if (resultArray != NULL)
            (*env)->SetByteArrayRegion(env, resultArray, 0, (jsize) written, (const jbyte*) output);
    }

    free(output);
    free(data);
    if (result != 0 && result != GHOSTTY_OUT_OF_SPACE) {
        throw_runtime_exception(env, "Failed to encode libghostty-vt paste data");
        return NULL;
    }
    return resultArray;
}

JNIEXPORT jboolean JNICALL Java_com_termux_terminal_JNI_ghosttySetSelection(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jint x1,
        jint y1,
        jint x2,
        jint y2,
        jboolean active)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return JNI_FALSE;

    if (active != JNI_TRUE) {
        GhosttyResult result = gTerminalSet(bridge->terminal, 21, NULL);
        return result == 0 ? JNI_TRUE : JNI_FALSE;
    }

    GhosttySelection selection = {
        .size = sizeof(GhosttySelection),
        .rectangle = false,
    };
    if (!ghostty_grid_ref_from_termux(env, bridge, x1, y1, &selection.start) ||
        !ghostty_grid_ref_from_termux(env, bridge, x2, y2, &selection.end)) {
        gTerminalSet(bridge->terminal, 21, NULL);
        return JNI_FALSE;
    }

    GhosttyResult result = gTerminalSet(bridge->terminal, 21, &selection);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL Java_com_termux_terminal_JNI_ghosttyFormatSelection(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jint x1,
        jint y1,
        jint x2,
        jint y2,
        jboolean unwrap,
        jboolean trim)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return NULL;

    GhosttySelection selection = {
        .size = sizeof(GhosttySelection),
        .rectangle = false,
    };
    if (!ghostty_grid_ref_from_termux(env, bridge, x1, y1, &selection.start) ||
        !ghostty_grid_ref_from_termux(env, bridge, x2, y2, &selection.end)) {
        return NULL;
    }

    return ghostty_format_selection_bytes(env, bridge, &selection, unwrap == JNI_TRUE, trim == JNI_TRUE);
}

JNIEXPORT jbyteArray JNICALL Java_com_termux_terminal_JNI_ghosttySelectWord(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jint x,
        jint y)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return NULL;

    GhosttyGridRef ref = { .size = sizeof(GhosttyGridRef) };
    if (!ghostty_grid_ref_from_termux(env, bridge, x, y, &ref))
        return NULL;

    GhosttyTerminalSelectWordOptions options = {
        .size = sizeof(GhosttyTerminalSelectWordOptions),
        .ref = ref,
        .boundary_codepoints = NULL,
        .boundary_codepoints_len = 0,
    };
    GhosttySelection selection = {
        .size = sizeof(GhosttySelection),
        .rectangle = false,
    };
    GhosttyResult result = gTerminalSelectWord(bridge->terminal, &options, &selection);
    if (result == GHOSTTY_NO_VALUE)
        return (*env)->NewByteArray(env, 0);
    if (result != 0) {
        throw_runtime_exception(env, "Failed to select libghostty-vt word");
        return NULL;
    }

    return ghostty_format_selection_bytes(env, bridge, &selection, true, true);
}

JNIEXPORT jintArray JNICALL Java_com_termux_terminal_JNI_ghosttySelectWordBounds(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jint x,
        jint y)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return NULL;

    GhosttyGridRef ref = { .size = sizeof(GhosttyGridRef) };
    if (!ghostty_grid_ref_from_termux(env, bridge, x, y, &ref))
        return NULL;

    GhosttyTerminalSelectWordOptions options = {
        .size = sizeof(GhosttyTerminalSelectWordOptions),
        .ref = ref,
        .boundary_codepoints = NULL,
        .boundary_codepoints_len = 0,
    };
    GhosttySelection selection = {
        .size = sizeof(GhosttySelection),
        .rectangle = false,
    };
    GhosttyResult result = gTerminalSelectWord(bridge->terminal, &options, &selection);
    if (result == GHOSTTY_NO_VALUE)
        return NULL;
    if (result != 0) {
        throw_runtime_exception(env, "Failed to select libghostty-vt word bounds");
        return NULL;
    }

    jint values[4] = {0};
    if (!ghostty_termux_point_from_grid_ref(bridge, &selection.start, &values[0], &values[1]) ||
        !ghostty_termux_point_from_grid_ref(bridge, &selection.end, &values[2], &values[3])) {
        return NULL;
    }

    jintArray resultArray = (*env)->NewIntArray(env, 4);
    if (resultArray == NULL) return NULL;
    (*env)->SetIntArrayRegion(env, resultArray, 0, 4, values);
    return resultArray;
}

JNIEXPORT jbyteArray JNICALL Java_com_termux_terminal_JNI_ghosttyGetHyperlinkAtLocation(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context,
        jint x,
        jint y)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return NULL;

    GhosttyGridRef ref = { .size = sizeof(GhosttyGridRef) };
    if (!ghostty_grid_ref_from_termux(env, bridge, x, y, &ref))
        return (*env)->NewByteArray(env, 0);

    uint8_t stackBuffer[256];
    size_t written = 0;
    GhosttyResult result = gGridRefHyperlinkUri(&ref, stackBuffer, sizeof(stackBuffer), &written);
    uint8_t* output = stackBuffer;
    if (result == GHOSTTY_OUT_OF_SPACE && written > sizeof(stackBuffer)) {
        output = (uint8_t*) malloc(written);
        if (output == NULL) {
            throw_runtime_exception(env, "Failed to allocate libghostty-vt hyperlink URI buffer");
            return NULL;
        }
        result = gGridRefHyperlinkUri(&ref, output, written, &written);
    }

    jbyteArray resultArray = NULL;
    if (result == 0) {
        if (written > (size_t) INT32_MAX) {
            if (output != stackBuffer) free(output);
            throw_runtime_exception(env, "Hyperlink URI exceeds JNI array limits");
            return NULL;
        }
        resultArray = (*env)->NewByteArray(env, (jsize) written);
        if (resultArray != NULL && written > 0)
            (*env)->SetByteArrayRegion(env, resultArray, 0, (jsize) written, (const jbyte*) output);
    }

    if (output != stackBuffer)
        free(output);
    if (result != 0 && result != GHOSTTY_OUT_OF_SPACE) {
        throw_runtime_exception(env, "Failed to read libghostty-vt hyperlink URI");
        return NULL;
    }
    return resultArray;
}

// Scan the whole grid (scrollback + viewport) for OSC 8 hyperlinks in a single
// JNI crossing and return every discovered URI newline-separated as one UTF-8
// byte[]. Doing the per-cell walk natively avoids the ~columns*(rows+scrollback)
// JNI round-trips (100k+ on a full 2000-row transcript) that the previous
// per-cell getHyperlinkAtLocation approach forced onto the UI thread. A cell's
// URI is appended only when it differs from the previous cell's URI, collapsing
// links that span consecutive cells; the Java side de-duplicates the remainder.
JNIEXPORT jbyteArray JNICALL Java_com_termux_terminal_JNI_ghosttyGetHyperlinks(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jlong context)
{
    GhosttyBridgeContext* bridge = require_context(env, context);
    if (bridge == NULL) return NULL;

    uint16_t columns = 0;
    uint16_t rows = 0;
    uint64_t scrollback_rows = 0;
    if (gTerminalGet(bridge->terminal, 1, &columns) != 0 || columns == 0 ||
        gTerminalGet(bridge->terminal, 2, &rows) != 0 || rows == 0 ||
        gTerminalGet(bridge->terminal, 15, &scrollback_rows) != 0) {
        return (*env)->NewByteArray(env, 0);
    }

    int64_t total_rows = (int64_t) scrollback_rows + (int64_t) rows;
    if (total_rows <= 0) return (*env)->NewByteArray(env, 0);

    size_t out_cap = 1024;
    size_t out_len = 0;
    uint8_t* out = (uint8_t*) malloc(out_cap);
    if (out == NULL) {
        throw_runtime_exception(env, "Failed to allocate hyperlink scan buffer");
        return NULL;
    }

    uint8_t uri[2048];
    uint8_t prev[2048];
    size_t prev_len = 0;

    for (int64_t sy = 0; sy < total_rows && sy <= (int64_t) UINT32_MAX; sy++) {
        for (uint16_t x = 0; x < columns; x++) {
            GhosttyPoint point = {
                .tag = GHOSTTY_POINT_TAG_SCREEN,
                .value = { .coordinate = { .x = x, .y = (uint32_t) sy } },
            };
            GhosttyGridRef ref = { .size = sizeof(GhosttyGridRef), .node = NULL, .x = 0, .y = 0 };
            GhosttyResult refResult = gTerminalGridRef(bridge->terminal, point, &ref);
            if (refResult != 0) {
                if (refResult != GHOSTTY_NO_VALUE)
                    (*env)->ExceptionClear(env);
                prev_len = 0;
                continue;
            }

            size_t written = 0;
            GhosttyResult r = gGridRefHyperlinkUri(&ref, uri, sizeof(uri), &written);
            if (r != 0 || written == 0) {
                // OUT_OF_SPACE (URI longer than our stack buffer) is treated as
                // "no link here"; such URIs are pathologically rare.
                prev_len = 0;
                continue;
            }

            if (written == prev_len && memcmp(uri, prev, written) == 0)
                continue; // same URI as the previous cell — same link run

            memcpy(prev, uri, written);
            prev_len = written;

            size_t need = out_len + written + 1;
            if (need > out_cap) {
                size_t new_cap = out_cap * 2;
                while (new_cap < need) new_cap *= 2;
                uint8_t* new_out = (uint8_t*) realloc(out, new_cap);
                if (new_out == NULL) {
                    free(out);
                    throw_runtime_exception(env, "Failed to grow hyperlink scan buffer");
                    return NULL;
                }
                out = new_out;
                out_cap = new_cap;
            }
            memcpy(out + out_len, uri, written);
            out_len += written;
            out[out_len++] = (uint8_t) '\n';
        }
    }

    if (out_len > (size_t) INT32_MAX) {
        free(out);
        throw_runtime_exception(env, "Hyperlink scan exceeds JNI array limits");
        return NULL;
    }
    jbyteArray resultArray = (*env)->NewByteArray(env, (jsize) out_len);
    if (resultArray != NULL && out_len > 0)
        (*env)->SetByteArrayRegion(env, resultArray, 0, (jsize) out_len, (const jbyte*) out);
    free(out);
    return resultArray;
}
