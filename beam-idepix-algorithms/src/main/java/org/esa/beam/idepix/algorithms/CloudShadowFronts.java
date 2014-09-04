/*
 * Copyright (C) 2010 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.beam.idepix.algorithms;

import org.esa.beam.framework.datamodel.GeoCoding;
import org.esa.beam.framework.datamodel.GeoPos;
import org.esa.beam.framework.datamodel.PixelPos;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.idepix.util.Bresenham;
import org.esa.beam.util.math.MathUtils;

import java.awt.Rectangle;

/**
 * Cloud shadow algorithm based on fronts
 */
public abstract class CloudShadowFronts {

    private static final int MEAN_EARTH_RADIUS = 6372000;

    private final int cloudFlagBit;
    private final int cloudShadowFlagBit;

    private final GeoCoding geoCoding;
    private final Rectangle sourceRectangle;
    private final Rectangle targetRectangle;

    private final Tile targetFlagTile;
    private final Tile szaTile;
    private final Tile saaTile;
    private final Tile ctpTile;
    private final Tile altTile;

    public CloudShadowFronts(int cloudFlagBit, int cloudShadowFlagBit, GeoCoding geoCoding, Tile targetFlagTile,
                             Rectangle sourceRectangle,
                             Tile szaTile, Tile saaTile,
                             Tile ctpTile, Tile altTile) {
        this.cloudFlagBit = cloudFlagBit;
        this.cloudShadowFlagBit = cloudShadowFlagBit;
        this.geoCoding = geoCoding;
        this.targetFlagTile = targetFlagTile;
        this.sourceRectangle = sourceRectangle;
        this.targetRectangle = targetFlagTile.getRectangle();
        this.szaTile = szaTile;
        this.saaTile = saaTile;
        this.ctpTile = ctpTile;
        this.altTile = altTile;
    }

    protected abstract boolean isCloudForShadow(int x, int y);


    public void computeCloudShadow() {
        final int h = targetRectangle.height;
        final int w = targetRectangle.width;
        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;

        boolean[][] isCloudShadow = new boolean[w][h];
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                final boolean isCloud = targetFlagTile.getSampleBit(x, y, cloudFlagBit);
                if (!isCloud) {
                    isCloudShadow[x - x0][y - y0] = getCloudShadow(x, y);
                    targetFlagTile.setSample(x, y, cloudShadowFlagBit, isCloudShadow[x - x0][y - y0]);
                }
            }
        }

        // first 'post-correction': fill gaps surrounded by other cloud or cloud shadow pixels
        for (int y = targetRectangle.y; y < targetRectangle.y + targetRectangle.height; y++) {
            for (int x = targetRectangle.x; x < targetRectangle.x + targetRectangle.width; x++) {
                final boolean is_cloud = targetFlagTile.getSampleBit(x, y, cloudFlagBit);
                if (!is_cloud) {
                    final boolean pixelSurroundedByClouds = isPixelSurrounded(x, y, targetFlagTile, targetRectangle, cloudFlagBit);
                    final boolean pixelSurroundedByCloudShadow = isPixelSurroundedByCloudShadow(x, y, isCloudShadow);

                    if (pixelSurroundedByClouds || pixelSurroundedByCloudShadow) {
                        targetFlagTile.setSample(x, y, cloudShadowFlagBit, true);
                    }
                }
            }
        }

        // second post-correction, called 'belt' (why??): flag a pixel as cloud shadow if neighbour pixel is shadow
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                final boolean is_cloud = targetFlagTile.getSampleBit(x, y, cloudFlagBit);
                if (!is_cloud) {
                    performCloudShadowBeltCorrection(x, y, isCloudShadow);
                }
            }
        }
    }

    private boolean isPixelSurroundedByCloudShadow(int x, int y, boolean[][] isCloudShadow) {
        // check if pixel is surrounded by other cloud shadow pixels
        int surroundingPixelCount = 0;
        for (int i = x - 1; i <= x + 1; i++) {
            for (int j = y - 1; j <= y + 1; j++) {
                if (targetRectangle.contains(i, j)) {
                    if (isCloudShadow[i - targetRectangle.x][j - targetRectangle.y]) {
                        surroundingPixelCount++;
                    }
                }
            }
        }
        return surroundingPixelCount * 1.0 / 9 >= 0.7; // at least 6 pixel in a 3x3 box
    }


    private void performCloudShadowBeltCorrection(int x, int y, boolean[][] isCloudShadow) {
        // flag a pixel as cloud shadow if neighbour pixel is shadow
        for (int i = x - 1; i <= x + 1; i++) {
            for (int j = y - 1; j <= y + 1; j++) {
                if (targetRectangle.contains(i, j) && isCloudShadow[i - targetRectangle.x][j - targetRectangle.y]) {
                    targetFlagTile.setSample(x, y, cloudShadowFlagBit, true);
                    break;
                }
            }
        }
    }

    public static  boolean isPixelSurrounded(int x, int y, Tile sourceFlagTile, Rectangle targetRectangle, int pixelFlag) {
        // check if pixel is surrounded by other pixels flagged as 'pixelFlag'
        int surroundingPixelCount = 0;
        for (int i = x - 1; i <= x + 1; i++) {
            for (int j = y - 1; j <= y + 1; j++) {
                if (sourceFlagTile.getRectangle().contains(i, j)) {
                    boolean is_flagged = sourceFlagTile.getSampleBit(i, j, pixelFlag);
                    if (is_flagged && targetRectangle.contains(i, j)) {
                        surroundingPixelCount++;
                    }
                }
            }
        }
        return (surroundingPixelCount * 1.0 / 9 >= 0.7);  // at least 6 pixel in a 3x3 box
    }

    private boolean getCloudShadow(int x, int y) {

        final double sza = szaTile.getSampleDouble(x, y);
        final double saa = saaTile.getSampleDouble(x, y);
        double alt = 0;
        if (altTile != null) {
            alt = altTile.getSampleDouble(x, y);
            if (alt < 0) {
                alt = 0; // dont'n use bathimetry
            }
        }
        final double saaRad = Math.toRadians(saa);

        final GeoPos geoPos = geoCoding.getGeoPos(new PixelPos(x, y), null);
        double tanSza = Math.tan(Math.toRadians(90.0 - sza));
        final double cloudHeightMax = 12_000;
        final double cloudDistanceMax = cloudHeightMax / tanSza;

        GeoPos endGeoPoint = lineWithAngle(geoPos, cloudDistanceMax, saaRad + Math.PI);
        PixelPos endPixPoint = geoCoding.getPixelPos(endGeoPoint, null);
        if (endPixPoint.x == -1 || endPixPoint.y == -1) {
            return false;
        }
        java.util.List<PixelPos> pathPixels = Bresenham.getPathPixels(x, y, Math.round(endPixPoint.x), Math.round(endPixPoint.y), sourceRectangle);

        for (PixelPos pathPixel : pathPixels) {

            final int xCurrent = (int) pathPixel.getX();
            final int yCurrent = (int) pathPixel.getY();

            if (sourceRectangle.contains(xCurrent, yCurrent)) {
                if (isCloudForShadow(xCurrent, yCurrent)) {
                    final GeoPos geoPosCurrent = geoCoding.getGeoPos(new PixelPos(xCurrent, yCurrent), null);
                    final double cloudSearchHeight = (computeDistance(geoPos, geoPosCurrent) * tanSza) + alt;
                    final float cloudHeight = computeHeightFromPressure(ctpTile.getSampleFloat(xCurrent, yCurrent));
                    if (cloudSearchHeight <= cloudHeight + 300) {
                        float cloudBase = getCloudBase(xCurrent, yCurrent);
                        // cloud thickness should also be at least 300m (OD, 2012/08/02)
                        cloudBase = (float) Math.min(cloudHeight - 300.0, cloudBase);
                        // cloud base should be at least at 300m
                        cloudBase = (float) Math.max(300.0, cloudBase);
                        if (cloudSearchHeight >= cloudBase - 300) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }


    private float getCloudBase(int x, int y) {
        float cb;

        // computes the cloud base in metres
        if (ctpTile == null) {
            cb = 0.0f;
        } else {
            cb = computeHeightFromPressure(ctpTile.getSampleFloat(x, y));
            for (int i = x - 1; i <= x + 1; i++) {
                for (int j = y - 1; j <= y + 1; j++) {
                    if (sourceRectangle.contains(i, j)) {
                        final float neighbourCloudBase = computeHeightFromPressure(ctpTile.getSampleFloat(i, j));
                        cb = Math.min(cb, neighbourCloudBase);
                    }
                }
            }
        }
        return cb;
    }

    public static GeoPos lineWithAngle(GeoPos startPoint, double lengthInMeters, double azimuthAngleInRadiance) {
        //deltaX and deltaY are the corrections to apply to get the point
        final double deltaX = lengthInMeters * Math.sin(azimuthAngleInRadiance);
        final double deltaY = lengthInMeters * Math.cos(azimuthAngleInRadiance);

        // distLat and distLon are in degrees
        final float distLat = (float) (-(deltaY / MEAN_EARTH_RADIUS) * MathUtils.RTOD);
        final float distLon = (float) (-(deltaX / (MEAN_EARTH_RADIUS * Math
                .cos(startPoint.lat * MathUtils.DTOR))) * MathUtils.RTOD);

        return new GeoPos(startPoint.lat + distLat, startPoint.lon + distLon);
    }

    private float computeHeightFromPressure(float pressure) {
        return (float) (-8000 * Math.log(pressure / 1013.0f));
    }

    private double computeDistance(GeoPos geoPos1, GeoPos geoPos2) {
        final float lon1 = geoPos1.getLon();
        final float lon2 = geoPos2.getLon();
        final float lat1 = geoPos1.getLat();
        final float lat2 = geoPos2.getLat();

        final double cosLat1 = Math.cos(MathUtils.DTOR * lat1);
        final double cosLat2 = Math.cos(MathUtils.DTOR * lat2);
        final double sinLat1 = Math.sin(MathUtils.DTOR * lat1);
        final double sinLat2 = Math.sin(MathUtils.DTOR * lat2);

        final double delta = MathUtils.DTOR * (lon2 - lon1);
        final double cosDelta = Math.cos(delta);
        final double sinDelta = Math.sin(delta);

        final double y = Math.sqrt(Math.pow(cosLat2 * sinDelta, 2) + Math.pow(cosLat1 * sinLat2 - sinLat1 * cosLat2 * cosDelta, 2));
        final double x = sinLat1 * sinLat2 + cosLat1 * cosLat2 * cosDelta;

        final double ad = Math.atan2(y, x);

        return ad * MEAN_EARTH_RADIUS;
    }
}
