package com.termux.terminal;

import java.util.Arrays;

public final class TerminalKittyGraphicsPlacement {

    public static final int FORMAT_RGB = 0;
    public static final int FORMAT_RGBA = 1;
    public static final int COMPRESSION_NONE = 0;

    public final int imageId;
    public final int placementId;
    public final int zIndex;
    public final int xOffset;
    public final int yOffset;
    public final int imageWidth;
    public final int imageHeight;
    public final int imageFormat;
    public final int imageCompression;
    public final int pixelWidth;
    public final int pixelHeight;
    public final int gridColumns;
    public final int gridRows;
    public final int viewportColumn;
    public final int viewportRow;
    public final int sourceX;
    public final int sourceY;
    public final int sourceWidth;
    public final int sourceHeight;
    public final byte[] imageData;
    public final int imageDataHash;

    public TerminalKittyGraphicsPlacement(int imageId, int placementId, int zIndex, int xOffset, int yOffset,
                                          int imageWidth, int imageHeight, int imageFormat, int imageCompression,
                                          int pixelWidth, int pixelHeight, int gridColumns, int gridRows,
                                          int viewportColumn, int viewportRow, int sourceX, int sourceY,
                                          int sourceWidth, int sourceHeight, byte[] imageData) {
        this.imageId = imageId;
        this.placementId = placementId;
        this.zIndex = zIndex;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.imageFormat = imageFormat;
        this.imageCompression = imageCompression;
        this.pixelWidth = pixelWidth;
        this.pixelHeight = pixelHeight;
        this.gridColumns = gridColumns;
        this.gridRows = gridRows;
        this.viewportColumn = viewportColumn;
        this.viewportRow = viewportRow;
        this.sourceX = sourceX;
        this.sourceY = sourceY;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.imageData = imageData != null ? imageData : new byte[0];
        this.imageDataHash = Arrays.hashCode(this.imageData);
    }

    public boolean isTextureUploadSupported() {
        return imageCompression == COMPRESSION_NONE &&
            (imageFormat == FORMAT_RGB || imageFormat == FORMAT_RGBA) &&
            imageWidth > 0 && imageHeight > 0 && imageData.length > 0;
    }
}
