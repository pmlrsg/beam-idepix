package org.esa.beam.idepix.algorithms.occci;

/**
 * IDEPIX pixel identification algorithm for OC-CCI/MODIS
 * todo: complete
 *
 * @author olafd
 */
public class OccciModisAlgorithm extends OccciAlgorithm {

    // as long as we have no Schiller, CLOUD thresholds experimentally selected just from A2009125001500.L1B_LAC:
    private static final double THRESH_BRIGHT_CLOUD_AMBIGUOUS = 0.07;
    private static final double THRESH_BRIGHT_CLOUD_SURE = 0.15;
    // SNOW_ICE thresholds experimentally selected just from MOD021KM.A2014121.0155.006.2014121132820.hdf
    // more investigations needed
    private static final double THRESH_BRIGHT_SNOW_ICE = 0.25;
    private static final double THRESH_NDSI_SNOW_ICE = 0.8;

    @Override
    public boolean isSnowIce() {

        // for MODIS ALL NN, nnOutput has one element:
        // nnOutput[0] =
        // 0 < x < 2.0 : clear
        // 2.0 < x < 3.35 : noncl / semitransparent cloud --> cloud ambiguous
        // 3.35 < x < 4.2 : cloudy --> cloud sure
        // 4.2 < x : clear snow/ice
        boolean isSnowIceFromNN;
        if (nnOutput != null) {
            isSnowIceFromNN =  nnOutput[0] > 4.2 && nnOutput[0] <= 5.0;    // separation numbers from HS, 20140923
        } else {
            // fallback
            // needs ndsi and brightness
            // MERIS: ndsi depends on rho_toa_865,885; brightness depends on rho_ag (bottom of Rayleigh)
            // maybe we can forget the Rayleigh (it's small)
            // MODIS: for slope use bands 16 (869nm) and 7 (2130nm, 500m spatial), threshold to be adjusted
            // for brightness use band 16 (Rayleigh corrected?)
            isSnowIceFromNN =  (!isInvalid() && brightValue() > THRESH_BRIGHT_SNOW_ICE && ndsiValue() > THRESH_NDSI_SNOW_ICE);
            // todo: use MP stuff as fallback or in combination?
        }

        // MP additional criteria:

        // 0.95 < (EV_500_Aggr1km_RefSB_4 / EV_500_Aggr1km_RefSB_3 ) < 1 -> ice confidence 1 (not in sun glint area)
        // todo: does not work, see Madagaskar example A2003062103500.L1B_LAC
//        final double reflRatio4By3 = refl[3]/refl[2];
//        final boolean isSnowIceFromReflRatio = !isGlintRisk() && reflRatio4By3 > 0.95 && reflRatio4By3 < 1.0;
        final boolean isSnowIceFromReflRatio = false;

        // f1: EV_500_Aggr1km_RefSB_3 (469.0nm) [R]
        // f2: EV_500_Aggr1km_RefSB_5 (1240.0nm) [G]
        // f3: EV_500_Aggr1km_RefSB_7 (2130.0nm) [B]
        // f1 > 0.3 && f1/f2 > 2 && f1/f3 > 3 => SNOW/ICE
        // todo: does not work, no ice over Antarctica, example A2003062103500.L1B_LAC
//        final double reflR = refl[2];
//        final double reflG = refl[4];
//        final double reflB = refl[6];
//        final boolean isSnowIceFromRGB = reflR > 0.3 && reflR/reflG > 2.0 && reflR/reflB > 3.0;
        final boolean isSnowIceFromRGB = false;

        return isSnowIceFromNN || isSnowIceFromReflRatio || isSnowIceFromRGB;
    }

    @Override
    public boolean isCloud() {
        return isCloudAmbiguous() || isCloudSure();
    }

    @Override
    public boolean isCloudAmbiguous() {
        if (isCloudSure() || isSnowIce()) {   // this check has priority
            return false;
        }

        // for MODIS ALL NN, nnOutput has one element:
        // nnOutput[0] =
        // 0 < x < 2.0 : clear
        // 2.0 < x < 3.35 : noncl / semitransparent cloud --> cloud ambiguous
        // 3.35 < x < 4.2 : cloudy --> cloud sure
        // 4.2 < x : clear snow/ice
        boolean isCloudAmbiguousFromNN;
        if (nnOutput != null) {
            isCloudAmbiguousFromNN = nnOutput[0] > 2.0 && nnOutput[0] <= 3.35;    // separation numbers from HS, 20140923
        } else {
            // fallback
            isCloudAmbiguousFromNN = (brightValue() > THRESH_BRIGHT_CLOUD_AMBIGUOUS);
            // todo: use MP stuff as fallback or in combination?
        }

        // MP additional criteria:

        // Whiteness Criteria
        // (A bright and spectrally flat signal)
        // c1: EV_250_Aggr1km_RefSB_1 / EV_500_Aggr1km_RefSB_3
        // c2: EV_500_Aggr1km_RefSB_4/ EV_500_Aggr1km_RefSB_3
        // c3: EV_250_Aggr1km_RefSB_1 / EV_500_Aggr1km_RefSB_4

        // c1 > 0.87 && c2 > 0.9 && c3 > 0.97 --> cloud sure
        final float c1 = whiteValue(0, 2);
        final float c2 = whiteValue(3, 2);
        final float c3 = whiteValue(0, 3);

        boolean isCloudAmbiguousFromWhitenesses;
        final double m = Math.min(Math.min(refl[0], refl[2]), refl[3]);
        if (isLand()) {
            isCloudAmbiguousFromWhitenesses = m > 0.3 && c1 > 0.85 && c2 > 0.86 && c3 > 0.86 && c1 <= 0.96 && c2 <= 0.93 && c3 <= 0.05;
        } else {
            isCloudAmbiguousFromWhitenesses = m > 0.3 && c1 > 0.6 && c2 > 0.74 && c3 > 0.9;
        }

        return isCloudAmbiguousFromNN || isCloudAmbiguousFromWhitenesses;
    }

    @Override
    public boolean isCloudSure() {
        if (isSnowIce()) {   // this has priority
            return false;
        }

        // for MODIS ALL NN, nnOutput has one element:
        // nnOutput[0] =
        // 0 < x < 2.0 : clear
        // 2.0 < x < 3.35 : noncl / semitransparent cloud --> cloud ambiguous
        // 3.35 < x < 4.2 : cloudy --> cloud sure
        // 4.2 < x : clear snow/ice
        boolean isCloudSureFromNN;
        if (nnOutput != null) {
            isCloudSureFromNN =  nnOutput[0] > 3.35 && nnOutput[0] <= 4.2;   // ALL NN separation numbers from HS, 20140923
        } else {
            // fallback
            isCloudSureFromNN =  (brightValue() > THRESH_BRIGHT_CLOUD_SURE);
            // todo: use MP stuff as fallback or in combination?
        }

        // MP additional criteria:

        // Whiteness Criteria
        // (A bright and spectrally flat signal)
        // c1: EV_250_Aggr1km_RefSB_1 / EV_500_Aggr1km_RefSB_3
        // c2: EV_500_Aggr1km_RefSB_4/ EV_500_Aggr1km_RefSB_3
        // c3: EV_250_Aggr1km_RefSB_1 / EV_500_Aggr1km_RefSB_4

        // c1 > 0.87 && c2 > 0.9 && c3 > 0.97 --> cloud sure
        final float c1 = whiteValue(0, 2);
        final float c2 = whiteValue(3, 2);
        final float c3 = whiteValue(0, 3);

        boolean isCloudSureFromWhitenesses;
        final double m = Math.min(Math.min(refl[0], refl[2]), refl[3]);
        if (isLand()) {
            isCloudSureFromWhitenesses = m > 0.7 && c1 > 0.96 && c2 > 0.93 && c3 > 0.95 && c1 < 1.04 && c2 < 1.05 && c3 < 1.05;
        } else {
//            isCloudSureFromWhitenesses = m > 0.3 && c1 > 0.87 && c2 > 0.9 && c3 > 0.97;
            isCloudSureFromWhitenesses = c1 > 0.87 && c2 > 0.9 && c3 > 0.97;
        }

        return isCloudSureFromNN || isCloudSureFromWhitenesses;
    }

    @Override
    public boolean isCloudBuffer() {
        // is applied in post processing!
        return false;
    }

    @Override
    public boolean isCloudShadow() {
        // will be applied in post processing once we have an appropriate algorithm
        return false;
    }

    @Override
    public boolean isMixedPixel() {
        // todo
        // unmixing using MERIS bands 7, 9, 10, 12
        return false;
    }

    @Override
    public boolean isGlintRisk() {
        // todo
        // depends on geometry, windspeed and rho_toa_865
        // MODIS: we have rho_toa_865, wind components are required!
        // MODIS: use L2 product if available
        return false;
    }

    ///////////////////////////////////////////////////////////////////////
    // feature values

    @Override
    public float brightValue() {
        // use EV_250_Aggr1km_RefSB_1
        return (float) refl[0];
    }

    @Override
    public float whiteValue(int numeratorIndex, int denominatorIndex) {
        return (float) (refl[numeratorIndex]/refl[denominatorIndex]);
    }

    @Override
    public float ndsiValue() {
        // use EV_250_Aggr1km_RefSB_1, EV_500_Aggr1km_RefSB_7
        return (float) ((refl[0] - refl[6])/(refl[0] + refl[6]));
    }

}
