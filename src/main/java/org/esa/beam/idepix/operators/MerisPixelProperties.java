package org.esa.beam.idepix.operators;

import org.esa.beam.idepix.util.IdepixUtils;
import org.esa.beam.util.math.MathUtils;

/**
 * This class represents pixel properties as derived from MERIS L1b data
 *
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
class MerisPixelProperties implements PixelProperties {

    private static final float BRIGHTWHITE_THRESH = 1.5f;
    private static final float NDSI_THRESH = 0.014f;
    private static final float PRESSURE_THRESH = 0.9f;
    private static final float CLOUD_THRESH = 1.65f;
    private static final float UNCERTAINTY_VALUE = 0.5f;
    private static final float LAND_THRESH = 0.9f;
    private static final float WATER_THRESH = 0.9f;
    private static final float BRIGHT_THRESH = 0.5f;
    private static final float WHITE_THRESH = 0.9f;
    private static final float BRIGHT_FOR_WHITE_THRESH = 0.8f;
    private static final float NDVI_THRESH = 0.4f;
    private static final float TEMPERATURE_THRESH = 0.9f;

    private static final float GLINT_THRESH =  0.9f;

    public static final int L1B_F_LAND = 4;
    private float brr442;
    private float brr442Thresh;
    private float p1;
    private float pscatt;
    private float pbaro;

    private boolean l1FlagLand;
    private float[] refl;
    private float[] brr;


    @Override
    public boolean isBrightWhite() {
        return (whiteValue() + brightValue() > BRIGHTWHITE_THRESH);
    }

    @Override
    public boolean isCloud() {
        return (whiteValue() + brightValue() + pressureValue() + temperatureValue() > CLOUD_THRESH && !isClearSnow());
    }

    @Override
    public boolean isCloudBuffer() {
//        return combinedCloudFlagShadow;  // todo: activate when ready
        return false;
    }

    @Override
    public boolean isClearLand() {
        if (isInvalid()) {
            return false;
        }
        float landValue;

        if (!MathUtils.equalValues(radiometricLandValue(), UNCERTAINTY_VALUE)) {
            landValue = radiometricLandValue();
        } else if (aPrioriLandValue() > UNCERTAINTY_VALUE) {
            landValue = aPrioriLandValue();
        } else {
            return false; // this means: if we have no information about land, we return isClearLand = false
        }
        return (!isCloud() && landValue > LAND_THRESH);
    }

    @Override
    public boolean isClearWater() {
        if (isInvalid()) {
            return false;
        }
         float waterValue;
        if (!MathUtils.equalValues(radiometricWaterValue(), UNCERTAINTY_VALUE)) {
            waterValue = radiometricWaterValue();
        } else if (aPrioriWaterValue() > UNCERTAINTY_VALUE) {
            waterValue = aPrioriWaterValue();
        } else {
            return false; // this means: if we have no information about water, we return isClearWater = false
        }
        return (!isCloud() && waterValue > WATER_THRESH);
    }

    @Override
    public boolean isClearSnow() {
        return (!isInvalid() && isBrightWhite() && ndsiValue() > NDSI_THRESH);
    }

    @Override
    public boolean isLand() {
        return (!isInvalid() && aPrioriLandValue() > LAND_THRESH);
    }

    @Override
    public boolean isWater() {
        return (!isInvalid() && aPrioriWaterValue() > WATER_THRESH);
    }

    @Override
    public boolean isBright() {
        return (!isInvalid() && brightValue() > BRIGHT_THRESH);
    }

    @Override
    public boolean isWhite() {
        return (!isInvalid() && whiteValue() > WHITE_THRESH);
    }

    @Override
    public boolean isCold() {
        return (!isInvalid() && temperatureValue() > TEMPERATURE_THRESH);
    }

    @Override
    public boolean isVegRisk() {
        return (!isInvalid() && ndviValue() > NDVI_THRESH);
    }

    @Override
    public boolean isGlintRisk() {
        return (isWater() && isCloud() && (glintRiskValue() > GLINT_THRESH));
    }

    @Override
    public boolean isHigh() {
        return (!isInvalid() && pressureValue() > PRESSURE_THRESH);
    }

    @Override
    public boolean isInvalid() {
        return !IdepixUtils.areReflectancesValid(refl);
    }

    @Override
    public float brightValue() {
        if (brr442 <= 0.0 || brr442Thresh <= 0.0) {
            return IdepixConstants.NO_DATA_VALUE;
        }
        return brr442 / brr442Thresh;
    }

    @Override
    public float spectralFlatnessValue() {
        final double slope0 = IdepixUtils.spectralSlope(refl[0], refl[2],
                                                          IdepixConstants. MERIS_WAVELENGTHS[0], IdepixConstants. MERIS_WAVELENGTHS[2]);
        final double slope1 = IdepixUtils.spectralSlope(refl[4], refl[5],
                                                                  IdepixConstants. MERIS_WAVELENGTHS[4], IdepixConstants. MERIS_WAVELENGTHS[5]);
        final double slope2 = IdepixUtils.spectralSlope(refl[6], refl[9],
                                                                  IdepixConstants. MERIS_WAVELENGTHS[6], IdepixConstants. MERIS_WAVELENGTHS[9]);


        final double flatness = 1.0f - Math.abs(1000.0 * (slope0 + slope1 + slope2) / 3.0);
        float result = (float) Math.max(-1.0f, flatness);
        return result;
    }

    @Override
    public float whiteValue() {
        if (brightValue() > BRIGHT_FOR_WHITE_THRESH) {
            return 2 * spectralFlatnessValue() - 1;
        } else {
            return 0f;
        }
    }

    @Override
    public float temperatureValue() {
        return UNCERTAINTY_VALUE; 
    }

    @Override
    public float ndsiValue() {
        return (brr[11]-brr[12])/(brr[11]+brr[12]);
    }

    @Override
    public float ndviValue() {
        return (brr[9]-brr[4])/(brr[9]+brr[4]);
    }

    @Override
    public float pressureValue() {
        if (isLand()) {
            return 1.0f - p1/1000.0f;
            // test: use diff. pbaro - p1 instead:
//            return (float) (Math.min(1.0, (pbaro - p1)/2,00.0f));
            // this was checked, but we got many artifacts along coastlines.
            // --> to be further investigated
        } else if (isWater()) {
            return 1.0f - pscatt/1000.0f;
        } else {
            return UNCERTAINTY_VALUE;
        }
    }
    
    @Override
    public float glintRiskValue() {
        return UNCERTAINTY_VALUE;
    }

    @Override
    public float aPrioriLandValue() {
        if (isInvalid()) {
            return 0.5f;
        } else if (l1FlagLand) {
            return 1.0f;
        } else {
            return 0.0f;
        }
    }

    @Override
    public float aPrioriWaterValue() {
        if (isInvalid()) {
            return 0.5f;
        } else if (!l1FlagLand) {
            return 1.0f;
        } else return 0.0f;
    }

    @Override
    public float radiometricLandValue() {
        return UNCERTAINTY_VALUE;

        // todo: clarify value for REFL620_LAND_THRESH (not yet in ATBD) then implement the following
//        if (isCloud()) {
//            return UNCERTAINTY_VALUE;
//        }
//        if (refl[9] >= refl[6] && refl[6] > REFL620_LAND_THRESH) {
//            return 1.0f;
//        } else {
//            return 0.5f;
//        }
    }

    @Override
    public float radiometricWaterValue() {
         return UNCERTAINTY_VALUE;

        // todo: clarify value for REFL620_WATER_THRESH (not yet in ATBD) then implement the following
//        if (isCloud()) {
//            return UNCERTAINTY_VALUE;
//        }
//        if (refl[9] >= refl[6] && refl[6] > REFL620_WATER_THRESH) {
//            return 1.0f;
//        } else {
//            return 0.5f;
//        }
    }

    // setters for MERIS specific quantities
    public void setBrr442(float brr442) {
        this.brr442 = brr442;
    }

    public void setBrr442Thresh(float brr442Thresh) {
        this.brr442Thresh = brr442Thresh;
    }

    public void setRefl(float[] refl) {
        this.refl = refl;
    }

    public void setBrr(float[] brr) {
        this.brr = brr;
    }

    public void setP1(float p1) {
        this.p1 = p1;
    }

    public void setPscatt(float pscatt) {
        this.pscatt = pscatt;
    }

    public void setPBaro(float pbaro) {
        this.pbaro = pbaro;
    }

    public void setL1FlagLand(boolean l1FlagLand) {
        this.l1FlagLand = l1FlagLand;
    }
}
