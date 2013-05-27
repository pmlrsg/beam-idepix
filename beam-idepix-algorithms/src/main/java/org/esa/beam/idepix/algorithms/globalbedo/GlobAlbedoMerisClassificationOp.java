package org.esa.beam.idepix.algorithms.globalbedo;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.*;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.idepix.IdepixConstants;
import org.esa.beam.idepix.algorithms.SchillerAlgorithm;
import org.esa.beam.idepix.operators.BarometricPressureOp;
import org.esa.beam.idepix.operators.LisePressureOp;
import org.esa.beam.idepix.util.IdepixUtils;
import org.esa.beam.meris.brr.Rad2ReflOp;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.watermask.operator.WatermaskClassifier;

import java.awt.*;
import java.util.Map;

/**
 * Operator for GlobAlbedo MERIS cloud screening
 *
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
@OperatorMetadata(alias = "idepix.globalbedo.classification.meris",
                  version = "1.0",
                  authors = "Olaf Danne",
                  copyright = "(c) 2008, 2012 by Brockmann Consult",
                  description = "This operator provides cloud screening from MERIS data.")
public class GlobAlbedoMerisClassificationOp extends GlobAlbedoClassificationOp {

    @SourceProduct(alias = "cloud", optional = true)
    private Product cloudProduct;
    @SourceProduct(alias = "rayleigh", optional = true)
    private Product rayleighProduct;
    @SourceProduct(alias = "refl", optional = true)
    private Product rad2reflProduct;
    @SourceProduct(alias = "pressure", optional = true)
    private Product pressureProduct;
    @SourceProduct(alias = "pbaro", optional = true)
    private Product pbaroProduct;

    private Band[] merisReflBands;
    private Band[] merisBrrBands;
    private Band brr442Band;
    private Band brr442ThreshBand;
    private Band p1Band;
    private Band pbaroBand;
    private Band pscattBand;

    private Band pressureBand;
    private Band pbaroOutputBand;
    private Band p1OutputBand;
    private Band pscattOutputBand;

    private SchillerAlgorithm landNN = null;

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle rectangle, ProgressMonitor pm) throws OperatorException {
        // MERIS variables
        final Tile brr442Tile = getSourceTile(brr442Band, rectangle);
        final Tile brr442ThreshTile = getSourceTile(brr442ThreshBand, rectangle);
        final Tile p1Tile = getSourceTile(p1Band, rectangle);
        final Tile pbaroTile = getSourceTile(pbaroBand, rectangle);
        final Tile pscattTile = getSourceTile(pscattBand, rectangle);

        final Band merisL1bFlagBand = sourceProduct.getBand(EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME);
        final Tile merisL1bFlagTile = getSourceTile(merisL1bFlagBand, rectangle);

        Tile[] merisBrrTiles = new Tile[IdepixConstants.MERIS_BRR_BAND_NAMES.length];
        float[] merisBrr = new float[IdepixConstants.MERIS_BRR_BAND_NAMES.length];
        for (int i = 0; i < IdepixConstants.MERIS_BRR_BAND_NAMES.length; i++) {
            merisBrrTiles[i] = getSourceTile(merisBrrBands[i], rectangle);
        }

        Tile[] merisReflectanceTiles = new Tile[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS];
        float[] merisReflectance = new float[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS];
        for (int i = 0; i < EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS; i++) {
            merisReflectanceTiles[i] = getSourceTile(merisReflBands[i], rectangle);
        }

        GeoPos geoPos = null;
        final Band cloudFlagTargetBand = targetProduct.getBand(IdepixUtils.IDEPIX_CLOUD_FLAGS);
        final Tile cloudFlagTargetTile = targetTiles.get(cloudFlagTargetBand);
        try {
            for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
                checkForCancellation();
                for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {

                    byte waterMaskSample = WatermaskClassifier.INVALID_VALUE;
                    byte waterMaskFraction = WatermaskClassifier.INVALID_VALUE;
                    if (!gaUseL1bLandWaterFlag) {
                        final GeoCoding geoCoding = sourceProduct.getGeoCoding();
                        if (geoCoding.canGetGeoPos()) {
                            geoPos = geoCoding.getGeoPos(new PixelPos(x, y), geoPos);
                            waterMaskSample = strategy.getWatermaskSample(geoPos.lat, geoPos.lon);
                            if (gaUseWaterMaskFraction) {
                                waterMaskFraction = strategy.getWatermaskFraction(geoCoding, x, y);
                            }
                        }
                    }

                    // set up pixel properties for given instruments...
                    GlobAlbedoAlgorithm globAlbedoAlgorithm = createMerisAlgorithm(merisL1bFlagTile,
                                                                                   brr442Tile, p1Tile,
                                                                                   pbaroTile, pscattTile, brr442ThreshTile,
                                                                                   merisReflectanceTiles,
                                                                                   merisReflectance,
                                                                                   merisBrrTiles, merisBrr,
                                                                                   waterMaskSample,
                                                                                   waterMaskFraction,
                                                                                   y,
                                                                                   x);

                    setCloudFlag(cloudFlagTargetTile, y, x, globAlbedoAlgorithm);

                    if (landNN != null && !cloudFlagTargetTile.getSampleBit(x, y, IdepixConstants.F_CLOUD)) {
                        final int finalX = x;
                        final int finalY = y;
                        final Tile[] finalMerisRefl = merisReflectanceTiles;
                        SchillerAlgorithm.Accessor accessor = new SchillerAlgorithm.Accessor() {
                            @Override
                            public double get(int index) {
                                return finalMerisRefl[index].getSampleDouble(finalX, finalY);
                            }
                        };
                        float schillerCloud = landNN.compute(accessor);
                        if (schillerCloud > 1.4) {  // a magic number...
                            cloudFlagTargetTile.setSample(x, y, IdepixConstants.F_CLOUD, true);
                        }
                    }

                    // for given instrument, compute more pixel properties and write to distinct band
                    for (Band band : targetProduct.getBands()) {
                        final Tile targetTile = targetTiles.get(band);
                        setPixelSamples(band, targetTile, p1Tile, pbaroTile, pscattTile, y, x, globAlbedoAlgorithm);
                    }
                }
            }
            // set cloud buffer flags...
            if (gaLcCloudBuffer) {
                IdepixUtils.setCloudBufferLC(IdepixUtils.IDEPIX_CLOUD_FLAGS, cloudFlagTargetTile, rectangle);
            } else {
                setCloudBuffer(IdepixUtils.IDEPIX_CLOUD_FLAGS, cloudFlagTargetTile, rectangle);
            }

        } catch (Exception e) {
            throw new OperatorException("Failed to provide GA cloud screening:\n" + e.getMessage(), e);
        }
    }

    @Override
    public void setBands() {
        merisReflBands = new Band[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS];
        for (int i = 0; i < EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS; i++) {
            merisReflBands[i] = rad2reflProduct.getBand(Rad2ReflOp.RHO_TOA_BAND_PREFIX + "_" + (i + 1));
        }
        brr442Band = rayleighProduct.getBand("brr_2");
        merisBrrBands = new Band[IdepixConstants.MERIS_BRR_BAND_NAMES.length];
        for (int i = 0; i < IdepixConstants.MERIS_BRR_BAND_NAMES.length; i++) {
            merisBrrBands[i] = rayleighProduct.getBand(IdepixConstants.MERIS_BRR_BAND_NAMES[i]);
        }
        p1Band = pressureProduct.getBand(LisePressureOp.PRESSURE_LISE_P1);
        pbaroBand = pbaroProduct.getBand(BarometricPressureOp.PRESSURE_BAROMETRIC);
        pscattBand = pressureProduct.getBand(LisePressureOp.PRESSURE_LISE_PSCATT);
        brr442ThreshBand = cloudProduct.getBand("rho442_thresh_term");

        if (gaComputeSchillerClouds) {
            landNN = new SchillerAlgorithm(SchillerAlgorithm.Net.LAND);
        }
    }

    @Override
    public void extendTargetProduct() throws OperatorException {
        if (!gaComputeFlagsOnly && gaCopyPressure) {
            pressureBand = targetProduct.addBand("pressure_value", ProductData.TYPE_FLOAT32);
            IdepixUtils.setNewBandProperties(pressureBand, "Pressure", "hPa", IdepixConstants.NO_DATA_VALUE, true);
            pbaroOutputBand = targetProduct.addBand("pbaro_value", ProductData.TYPE_FLOAT32);
            IdepixUtils.setNewBandProperties(pbaroOutputBand, "Barometric Pressure", "hPa",
                                             IdepixConstants.NO_DATA_VALUE,
                                             true);
            p1OutputBand = targetProduct.addBand("p1_value", ProductData.TYPE_FLOAT32);
            IdepixUtils.setNewBandProperties(p1OutputBand, "P1 Pressure", "hPa", IdepixConstants.NO_DATA_VALUE,
                                             true);
            pscattOutputBand = targetProduct.addBand("pscatt_value", ProductData.TYPE_FLOAT32);
            IdepixUtils.setNewBandProperties(pscattOutputBand, "PScatt Pressure", "hPa",
                                             IdepixConstants.NO_DATA_VALUE,
                                             true);
        }
        if (gaCopyRadiances) {
            copyRadiances();
            ProductUtils.copyFlagBands(sourceProduct, targetProduct, true);
        }
    }

    private void copyRadiances() {
        for (int i = 0; i < EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS; i++) {
            ProductUtils.copyBand(EnvisatConstants.MERIS_L1B_SPECTRAL_BAND_NAMES[i], sourceProduct,
                                  targetProduct, true);
        }
        for (int i = 0; i < EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS; i++) {
            ProductUtils.copyBand(Rad2ReflOp.RHO_TOA_BAND_PREFIX + "_" + (i + 1), rad2reflProduct,
                                  targetProduct, true);
        }
    }

    private GlobAlbedoAlgorithm createMerisAlgorithm(Tile merisL1bFlagTile,
                                                     Tile brr442Tile, Tile p1Tile,
                                                     Tile pbaroTile, Tile pscattTile, Tile brr442ThreshTile,
                                                     Tile[] merisReflectanceTiles,
                                                     float[] merisReflectance,
                                                     Tile[] merisBrrTiles, float[] merisBrr,
                                                     byte watermask,
                                                     byte watermaskFraction,
                                                     int y,
                                                     int x) {
        GlobAlbedoMerisAlgorithm gaAlgorithm = new GlobAlbedoMerisAlgorithm();

        for (int i = 0; i < EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS; i++) {
            merisReflectance[i] = merisReflectanceTiles[i].getSampleFloat(x, y);
        }

        gaAlgorithm.setRefl(merisReflectance);
        for (int i = 0; i < IdepixConstants.MERIS_BRR_BAND_NAMES.length; i++) {
            merisBrr[i] = merisBrrTiles[i].getSampleFloat(x, y);
        }

        gaAlgorithm.setBrr(merisBrr);
        gaAlgorithm.setBrr442(brr442Tile.getSampleFloat(x, y));
        gaAlgorithm.setBrr442Thresh(brr442ThreshTile.getSampleFloat(x, y));
        gaAlgorithm.setP1(p1Tile.getSampleFloat(x, y));
        gaAlgorithm.setPBaro(pbaroTile.getSampleFloat(x, y));
        gaAlgorithm.setPscatt(pscattTile.getSampleFloat(x, y));
        gaAlgorithm.setApplyBlueDenseCloudAlgorithm(gaApplyBlueDenseCloudAlgorithm);

        if (gaUseL1bLandWaterFlag) {
            final boolean isLand = merisL1bFlagTile.getSampleBit(x, y, MERIS_L1B_F_LAND);
            gaAlgorithm.setL1FlagLand(isLand);
            setIsWater(watermask, gaAlgorithm);
        } else {
            if (gaUseWaterMaskFraction) {
                final boolean isLand = merisL1bFlagTile.getSampleBit(x, y, MERIS_L1B_F_LAND) &&
                        watermaskFraction < WATERMASK_FRACTION_THRESH;
                gaAlgorithm.setL1FlagLand(isLand);
                setIsWaterByFraction(watermaskFraction, gaAlgorithm);
            } else {
                final boolean isLand = merisL1bFlagTile.getSampleBit(x, y, MERIS_L1B_F_LAND) &&
                        !(watermask == WatermaskClassifier.WATER_VALUE);
                gaAlgorithm.setL1FlagLand(isLand);
                setIsWater(watermask, gaAlgorithm);
            }
        }

        return gaAlgorithm;
    }

    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(GlobAlbedoMerisClassificationOp.class, "idepix.globalbedo.classification.meris");
        }
    }

}
