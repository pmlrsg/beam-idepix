package org.esa.beam.idepix.algorithms.avhrrac;

import org.esa.beam.framework.datamodel.*;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.framework.gpf.pointop.*;
import org.esa.beam.idepix.util.IdepixUtils;
import org.esa.beam.idepix.util.SchillerNeuralNetWrapper;
import org.esa.beam.idepix.util.SunPosition;
import org.esa.beam.idepix.util.SunPositionCalculator;
import org.esa.beam.util.math.MathUtils;
import org.esa.beam.util.math.RsMathUtils;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * Basic operator for AVHRR pixel classification
 *
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
@OperatorMetadata(alias = "idepix.avhrrac.avisa.classification",
        version = "2.2",
        internal = true,
        authors = "Olaf Danne",
        copyright = "(c) 2014 by Brockmann Consult",
        description = "Basic operator for pixel classification from AVHRR L1b data " +
                "(uses old AVHRR AC test data (like '95070912_pr') read by avhrr-ac-directory-reader).")
public class AvhrrAcAvisaClassificationOp extends PixelOperator {

    // TODO: inherit from abstract classification operator, or remove if no longer needed

    @SourceProduct(alias = "aacl1b", description = "The source product.")
    Product sourceProduct;

    @SourceProduct(alias = "waterMask")
    private Product waterMaskProduct;

    @TargetProduct(description = "The target product.")
    Product targetProduct;

    // AvhrrAc parameters
    @Parameter(defaultValue = "true", label = " Copy input radiance bands (with albedo1/2 converted)")
    boolean aacCopyRadiances = true;

    @Parameter(defaultValue = "2", label = " Width of cloud buffer (# of pixels)")
    int aacCloudBufferWidth;

    @Parameter(defaultValue = "50", valueSet = {"50", "150"}, label = " Resolution of used land-water mask in m/pixel",
            description = "Resolution in m/pixel")
    int wmResolution;

    @Parameter(defaultValue = "true", label = " Consider water mask fraction")
    boolean aacUseWaterMaskFraction = true;

    @Parameter(defaultValue = "false", label = " Flip source images (check before if needed!)")
    private boolean flipSourceImages;

//    @Parameter(defaultValue = "false",
//               label = " Debug bands",
//               description = "Write further useful bands to target product.")
//    private boolean avhrracOutputDebug = false;

    @Parameter(defaultValue = "2.15",
            label = " Schiller NN cloud ambiguous lower boundary ",
            description = " Schiller NN cloud ambiguous lower boundary ")
    double avhrracSchillerNNCloudAmbiguousLowerBoundaryValue;

    @Parameter(defaultValue = "3.45",
            label = " Schiller NN cloud ambiguous/sure separation value ",
            description = " Schiller NN cloud ambiguous cloud ambiguous/sure separation value ")
    double avhrracSchillerNNCloudAmbiguousSureSeparationValue;

    @Parameter(defaultValue = "4.45",
            label = " Schiller NN cloud sure/snow separation value ",
            description = " Schiller NN cloud ambiguous cloud sure/snow separation value ")
    double avhrracSchillerNNCloudSureSnowSeparationValue;


    @Parameter(defaultValue = "20.0",
            label = " Reflectance 1 'brightness' threshold ",
            description = " Reflectance 1 'brightness' threshold ")
    double reflCh1Thresh;

    @Parameter(defaultValue = "20.0",
            label = " Reflectance 2 'brightness' threshold ",
            description = " Reflectance 2 'brightness' threshold ")
    double reflCh2Thresh;

    @Parameter(defaultValue = "1.0",
            label = " Reflectance 2/1 ratio threshold ",
            description = " Reflectance 2/1 ratio threshold ")
    double r2r1RatioThresh;

    @Parameter(defaultValue = "1.0",
            label = " Reflectance 3/1 ratio threshold ",
            description = " Reflectance 3/1 ratio threshold ")
    double r3r1RatioThresh;

    @Parameter(defaultValue = "-30.0",
            label = " Channel 4 brightness temperature threshold (C)",
            description = " Channel 4 brightness temperature threshold (C)")
    double btCh4Thresh;

    @Parameter(defaultValue = "-30.0",
            label = " Channel 5 brightness temperature threshold (C)",
            description = " Channel 5 brightness temperature threshold (C)")
    double btCh5Thresh;


    private static final String SCHILLER_AVHRRAC_NET_NAME = "6x3_114.1.net";

    private static final int ALBEDO_TO_RADIANCE = 0;
    private static final int RADIANCE_TO_ALBEDO = 1;

    private static final double NU_CH3 = 2694.0;
    private static final double NU_CH4 = 925.0;
    private static final double NU_CH5 = 839.0;

    ThreadLocal<SchillerNeuralNetWrapper> avhrracNeuralNet;

    AvhrrAcAuxdata.Line2ViewZenithTable vzaTable;

    private SunPosition sunPosition;
    private String dateString;


    public Product getSourceProduct() {
        // this is the source product for the ProductConfigurer
        return sourceProduct;
    }

    @Override
    public void prepareInputs() throws OperatorException {
        readSchillerNets();
        createTargetProduct();
        dateString = getProductDatestring();
        sunPosition = computeSunPosition(dateString);

        try {
            vzaTable = AvhrrAcAuxdata.getInstance().createLine2ViewZenithTable();
        } catch (IOException e) {
            // todo
            e.printStackTrace();
        }
    }

    @Override
    protected void computePixel(int x, int y, Sample[] sourceSamples, WritableSample[] targetSamples) {
        runAvhrrAcAlgorithm(x, y, sourceSamples, targetSamples);
    }

    // package local for testing
    static double computeRelativeAzimuth(double vaaRad, double saaRad) {
        return correctRelAzimuthRange(vaaRad, saaRad);
    }

    static double[] computeAzimuthAngles(double sza,
                                         GeoPos satPosition,
                                         GeoPos pointPosition,
                                         SunPosition sunPosition) {

        final double latPoint = pointPosition.getLat();
        final double lonPoint = pointPosition.getLon();

        final double latSat = satPosition.getLat();
        final double lonSat = satPosition.getLon();

        final double latPointRad = latPoint * MathUtils.DTOR;
        final double lonPointRad = lonPoint * MathUtils.DTOR;
        final double latSatRad = latSat * MathUtils.DTOR;
        final double lonSatRad = lonSat * MathUtils.DTOR;

        final double latSunRad = sunPosition.getLat() * MathUtils.DTOR;
        final double lonSunRad = sunPosition.getLon() * MathUtils.DTOR;
        final double greatCirclePointToSatRad = computeGreatCircleFromPointToSat(latPointRad, lonPointRad, latSatRad, lonSatRad);

        final double vaaRad = computeVaa(latPointRad, lonPointRad, latSatRad, lonSatRad, greatCirclePointToSatRad);
        final double saaRad = computeSaa(sza, latPointRad, lonPointRad, latSunRad, lonSunRad);

        return new double[]{saaRad, vaaRad};
    }

    // package local for testing
    static double correctRelAzimuthRange(double vaaRad, double saaRad) {
        double relAzimuth = saaRad - vaaRad;
        if (relAzimuth < -Math.PI) {
            relAzimuth += 2.0 * Math.PI;
        } else if (relAzimuth > Math.PI) {
            relAzimuth -= 2.0 * Math.PI;
        }
        return Math.abs(relAzimuth);
    }

    // package local for testing
    static double computeGreatCircleFromPointToSat(double latPointRad, double lonPointRad, double latSatRad, double lonSatRad) {
        // http://mathworld.wolfram.com/GreatCircle.html, eq. (5):
        final double greatCirclePointToSat = 0.001 * RsMathUtils.MEAN_EARTH_RADIUS *
                Math.acos(Math.cos(latPointRad) * Math.cos(latSatRad) * Math.cos(lonPointRad - lonSatRad) +
                        Math.sin(latPointRad) * Math.sin(latSatRad));

        //        return 2.0 * Math.PI * greatCirclePointToSat / (0.001 * RsMathUtils.MEAN_EARTH_RADIUS);
        return greatCirclePointToSat / (0.001 * RsMathUtils.MEAN_EARTH_RADIUS);
    }

    // package local for testing
    static SunPosition computeSunPosition(String ddmmyy) {
        final Calendar calendar = getDateAsCalendar(ddmmyy);
        return SunPositionCalculator.calculate(calendar);
    }

    // package local for testing
    static Calendar getDateAsCalendar(String ddmmyy) {
        final Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        int year = Integer.parseInt(ddmmyy.substring(4, 6));
        if (year < 50) {
            year = 2000 + year;
        } else {
            year = 1900 + year;
        }
        final int month = Integer.parseInt(ddmmyy.substring(2, 4)) - 1;
        final int day = Integer.parseInt(ddmmyy.substring(0, 2));
        calendar.set(year, month, day, 12, 0, 0);
        return calendar;
    }

    // package local for testing
    static double computeSaa(double sza, double latPointRad, double lonPointRad, double latSunRad, double lonSunRad) {
        double arg = (Math.sin(latSunRad) - Math.sin(latPointRad) * Math.cos(sza * MathUtils.DTOR)) /
                (Math.cos(latPointRad) * Math.sin(sza * MathUtils.DTOR));
        arg = Math.min(Math.max(arg, -1.0), 1.0);    // keep in range [-1.0, 1.0]
        double saaRad = Math.acos(arg);
        if (Math.sin(lonSunRad - lonPointRad) < 0.0) {
            saaRad = 2.0 * Math.PI - saaRad;
        }
        return saaRad;
    }

    // package local for testing
    static double computeVaa(double latPointRad, double lonPointRad, double latSatRad, double lonSatRad,
                             double greatCirclePointToSatRad) {
        double arg = (Math.sin(latSatRad) - Math.sin(latPointRad) * Math.cos(greatCirclePointToSatRad)) /
                (Math.cos(latPointRad) * Math.sin(greatCirclePointToSatRad));
        arg = Math.min(Math.max(arg, -1.0), 1.0);    // keep in range [-1.0, 1.0]
        double vaaRad = Math.acos(arg);
        if (Math.sin(lonSatRad - lonPointRad) < 0.0) {
            vaaRad = 2.0 * Math.PI - vaaRad;
        }

        return vaaRad;
    }


    private void readSchillerNets() {
        try (InputStream is = getClass().getResourceAsStream(SCHILLER_AVHRRAC_NET_NAME)) {
            avhrracNeuralNet = SchillerNeuralNetWrapper.create(is);
        } catch (IOException e) {
            throw new OperatorException("Cannot read Schiller neural nets: " + e.getMessage());
        }
    }

    private void setClassifFlag(WritableSample[] targetSamples, AvhrrAcAlgorithm algorithm) {
        targetSamples[0].set(AvhrrAcConstants.F_INVALID, algorithm.isInvalid());
        targetSamples[0].set(AvhrrAcConstants.F_CLOUD, algorithm.isCloud());
        targetSamples[0].set(AvhrrAcConstants.F_CLOUD_AMBIGUOUS, algorithm.isCloudAmbiguous());
        targetSamples[0].set(AvhrrAcConstants.F_CLOUD_SURE, algorithm.isCloudSure());
        targetSamples[0].set(AvhrrAcConstants.F_CLOUD_BUFFER, algorithm.isCloudBuffer());
        targetSamples[0].set(AvhrrAcConstants.F_CLOUD_SHADOW, algorithm.isCloudShadow());
        targetSamples[0].set(AvhrrAcConstants.F_SNOW_ICE, algorithm.isSnowIce());
        targetSamples[0].set(AvhrrAcConstants.F_GLINT_RISK, algorithm.isGlintRisk());
        targetSamples[0].set(AvhrrAcConstants.F_COASTLINE, algorithm.isCoastline());
        targetSamples[0].set(AvhrrAcConstants.F_LAND, algorithm.isLand());
        // test:
//        targetSamples[0].set(AvhrrAcConstants.F_LAND + 1, algorithm.isReflCh1Bright());
//        targetSamples[0].set(AvhrrAcConstants.F_LAND + 2, algorithm.isReflCh2Bright());
//        targetSamples[0].set(AvhrrAcConstants.F_LAND + 3, algorithm.isR2R1RatioAboveThresh());
//        targetSamples[0].set(AvhrrAcConstants.F_LAND + 4, algorithm.isR3R1RatioAboveThresh());
//        targetSamples[0].set(AvhrrAcConstants.F_LAND + 5, algorithm.isCh4BtAboveThresh());
//        targetSamples[0].set(AvhrrAcConstants.F_LAND + 6, algorithm.isCh5BtAboveThresh());
    }

    private void runAvhrrAcAlgorithm(int x, int y, Sample[] sourceSamples, WritableSample[] targetSamples) {
        AvhrrAcAlgorithm aacAlgorithm = new AvhrrAcAlgorithm();

        final double sza = sourceSamples[0].getDouble();
        final double vza = sourceSamples[1].getDouble();
        final double relAzi = sourceSamples[2].getDouble();
        //double vza = 45.0f;
//        double vza = vzaTable.getVza(x);
//        final double relAzi = computeRelativeAzimuth(x, y, sza);

        final GeoPos satPosition = computeSatPosition(y);
        final GeoPos pointPosition = getGeoPos(x, y);

        final double[] azimuthAngles = computeAzimuthAngles(sza, satPosition, pointPosition, sunPosition);
        final double saaRad = azimuthAngles[0];
        final double vaaRad = azimuthAngles[1];
        final double myRelAzi = computeRelativeAzimuth(saaRad, vaaRad) * MathUtils.RTOD;

        double[] avhrrRadiance = new double[AvhrrAcConstants.AVHRR_AC_RADIANCE_AVISA_BAND_NAMES.length];

        boolean compute = true;
        for (int i = 0; i < 5; i++) {
            avhrrRadiance[i] = sourceSamples[i + 3].getDouble();
            if (avhrrRadiance[i] < 0.0) {
                compute = false;
                break;
            }
        }

        if (compute) {

            aacAlgorithm.setRadiance(avhrrRadiance);

            float waterFraction = Float.NaN;
            // the water mask ends at 59 Degree south, stop earlier to avoid artefacts
            if (getGeoPos(x, y).lat > -58f) {
                waterFraction = sourceSamples[AvhrrAcConstants.SRC_USGS_WATERFRACTION].getFloat();
            }

            SchillerNeuralNetWrapper nnWrapper = avhrracNeuralNet.get();
            double[] inputVector = nnWrapper.getInputVector();
            inputVector[0] = sza;
            inputVector[1] = vza;
            inputVector[2] = relAzi;
            inputVector[3] = Math.sqrt(avhrrRadiance[0]);
            inputVector[4] = Math.sqrt(avhrrRadiance[1]);
            inputVector[5] = Math.sqrt(avhrrRadiance[3]);
            inputVector[6] = Math.sqrt(avhrrRadiance[4]);
            aacAlgorithm.setRadiance(avhrrRadiance);
            aacAlgorithm.setWaterFraction(waterFraction);

            double[] nnOutput = nnWrapper.getNeuralNet().calc(inputVector);

            aacAlgorithm.setNnOutput(nnOutput);
            aacAlgorithm.setAmbiguousLowerBoundaryValue(avhrracSchillerNNCloudAmbiguousLowerBoundaryValue);
            aacAlgorithm.setAmbiguousSureSeparationValue(avhrracSchillerNNCloudAmbiguousSureSeparationValue);
            aacAlgorithm.setSureSnowSeparationValue(avhrracSchillerNNCloudSureSnowSeparationValue);

            setClassifFlag(targetSamples, aacAlgorithm);
            targetSamples[1].set(nnOutput[0]);
            targetSamples[2].set(relAzi);
            targetSamples[3].set(myRelAzi);
            targetSamples[4].set(saaRad * MathUtils.RTOD);
            targetSamples[5].set(vaaRad * MathUtils.RTOD);

        } else {
            targetSamples[0].set(AvhrrAcConstants.F_INVALID, true);
            targetSamples[1].set(Float.NaN);
            targetSamples[2].set(Float.NaN);
            targetSamples[3].set(Float.NaN);
            targetSamples[4].set(Float.NaN);
            targetSamples[5].set(Float.NaN);
            avhrrRadiance[0] = Float.NaN;
            avhrrRadiance[1] = Float.NaN;
        }

        if (aacCopyRadiances) {
            for (int i = 0; i < AvhrrAcConstants.AVHRR_AC_RADIANCE_BAND_NAMES.length; i++) {
                targetSamples[6 + i].set(avhrrRadiance[i]);
            }
        }
    }

    private GeoPos computeSatPosition(int y) {
        return getGeoPos(sourceProduct.getSceneRasterWidth() / 2, y);    // LAC_NADIR = 1024.5
    }

    private int getDoy(String yymmdd) {
        return IdepixUtils.getDoyFromYYMMDD(yymmdd);
    }

    private String getProductDatestring() {
        // provides datestring as DDMMYY !!!
        // NSS.LHRR.NM.D04167.S0710.E0714.B1026464.GC

        Calendar cal = Calendar.getInstance();
        final int yearStartIndex = sourceProduct.getName().indexOf("NSS.LHRR.NM.D") + 13;
        final String yearString = sourceProduct.getName().substring(yearStartIndex, yearStartIndex + 2);
        int year = Integer.parseInt(yearString);
        if (year > 50) {
            year += 1900;
        } else {
            year += 2000;
        }
        cal.set(Calendar.YEAR, year);
        final int doyStartIndex = yearStartIndex + 2;
        final String doyString = sourceProduct.getName().substring(doyStartIndex, doyStartIndex + 3);
        final int doy = Integer.parseInt(doyString);
        cal.set(Calendar.DAY_OF_YEAR, doy);

        SimpleDateFormat dateFormat = new SimpleDateFormat("ddMMyy");
        dateFormat.setCalendar(cal);
        return dateFormat.format(cal.getTime());
    }

    private GeoPos getGeoPos(int x, int y) {
        final GeoPos geoPos = new GeoPos();
        final GeoCoding geoCoding = sourceProduct.getGeoCoding();
        final PixelPos pixelPos = new PixelPos(x, y);
        geoCoding.getGeoPos(pixelPos, geoPos);
        return geoPos;
    }

    @Override
    protected void configureSourceSamples(SampleConfigurer sampleConfigurer) throws OperatorException {
        int index = 0;
        sampleConfigurer.defineSample(index++, "sun_zenith");
        sampleConfigurer.defineSample(index++, "view_zenith");
        sampleConfigurer.defineSample(index++, "delta_azimuth");
        for (int i = 0; i < 5; i++) {
            sampleConfigurer.defineSample(index++, AvhrrAcConstants.AVHRR_AC_RADIANCE_AVISA_BAND_NAMES[i]);
        }
        sampleConfigurer.defineSample(index, AvhrrAcConstants.LAND_WATER_FRACTION_BAND_NAME, waterMaskProduct);
    }

    @Override
    protected void configureTargetSamples(SampleConfigurer sampleConfigurer) throws OperatorException {
        int index = 0;
        // the only standard band:
        sampleConfigurer.defineSample(index++, AvhrrAcConstants.CLASSIF_BAND_NAME);

        sampleConfigurer.defineSample(index++, AvhrrAcConstants.SCHILLER_NN_OUTPUT_BAND_NAME);

        sampleConfigurer.defineSample(index++, "rel_azimuth");
        sampleConfigurer.defineSample(index++, "rel_azimuth_computed");
        sampleConfigurer.defineSample(index++, "saa_computed");
        sampleConfigurer.defineSample(index++, "vaa_computed");

        // radiances:
        if (aacCopyRadiances) {
            for (int i = 0; i < AvhrrAcConstants.AVHRR_AC_RADIANCE_BAND_NAMES.length; i++) {
                sampleConfigurer.defineSample(index++, AvhrrAcConstants.AVHRR_AC_RADIANCE_BAND_NAMES[i]);
            }
        }
    }

    @Override
    protected void configureTargetProduct(ProductConfigurer productConfigurer) {
        productConfigurer.copyTimeCoding();
        productConfigurer.copyTiePointGrids();
        Band classifFlagBand = productConfigurer.addBand(AvhrrAcConstants.CLASSIF_BAND_NAME, ProductData.TYPE_INT16);

        classifFlagBand.setDescription("Pixel classification flag");
        classifFlagBand.setUnit("dl");
        FlagCoding flagCoding = AvhrrAcUtils.createAvhrrAcFlagCoding(AvhrrAcConstants.CLASSIF_BAND_NAME);
        classifFlagBand.setSampleCoding(flagCoding);
        getTargetProduct().getFlagCodingGroup().add(flagCoding);

        productConfigurer.copyGeoCoding();
        AvhrrAcUtils.setupAvhrrAcClassifBitmask(getTargetProduct());

        Band nnValueBand = productConfigurer.addBand(AvhrrAcConstants.SCHILLER_NN_OUTPUT_BAND_NAME, ProductData.TYPE_FLOAT32);
        nnValueBand.setDescription("Schiller NN output value");
        nnValueBand.setUnit("dl");
        nnValueBand.setNoDataValue(Float.NaN);
        nnValueBand.setNoDataValueUsed(true);

        Band relaziBand = productConfigurer.addBand("rel_azimuth", ProductData.TYPE_FLOAT32);
        relaziBand.setDescription("relative azimuth");
        relaziBand.setUnit("deg");
        relaziBand.setNoDataValue(Float.NaN);
        relaziBand.setNoDataValueUsed(true);

        Band relaziComputedBand = productConfigurer.addBand("rel_azimuth_computed", ProductData.TYPE_FLOAT32);
        relaziComputedBand.setDescription("relative azimuth computed");
        relaziComputedBand.setUnit("deg");
        relaziComputedBand.setNoDataValue(Float.NaN);
        relaziComputedBand.setNoDataValueUsed(true);

        Band saaComputedBand = productConfigurer.addBand("saa_computed", ProductData.TYPE_FLOAT32);
        saaComputedBand.setDescription("saa computed");
        saaComputedBand.setUnit("deg");
        saaComputedBand.setNoDataValue(Float.NaN);
        saaComputedBand.setNoDataValueUsed(true);

        Band vaaComputedBand = productConfigurer.addBand("vaa_computed", ProductData.TYPE_FLOAT32);
        vaaComputedBand.setDescription("vaa computed");
        vaaComputedBand.setUnit("deg");
        vaaComputedBand.setNoDataValue(Float.NaN);
        vaaComputedBand.setNoDataValueUsed(true);

        // radiances:
        if (aacCopyRadiances) {
            for (int i = 0; i < AvhrrAcConstants.AVHRR_AC_RADIANCE_BAND_NAMES.length; i++) {
                Band radianceBand = productConfigurer.addBand("radiance_" + (i + 1), ProductData.TYPE_FLOAT32);
                radianceBand.setDescription("TOA radiance band " + (i + 1));
                radianceBand.setUnit("mW/(m^2 sr cm^-1)");
                radianceBand.setNoDataValue(Float.NaN);
                radianceBand.setNoDataValueUsed(true);
            }
        }
    }

    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(AvhrrAcAvisaClassificationOp.class);
        }
    }
}
