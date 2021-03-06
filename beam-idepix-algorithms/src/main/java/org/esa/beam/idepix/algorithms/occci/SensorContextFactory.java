package org.esa.beam.idepix.algorithms.occci;

/**
 * todo: add comment
 *
 * @author olafd
 */
public class SensorContextFactory {
    public static SensorContext fromTypeString(String productTypeName) {
        if (productTypeName.equalsIgnoreCase("MOD021KM")
                || productTypeName.equalsIgnoreCase("MYD021KM")
                || productTypeName.equalsIgnoreCase("MODIS Level 1B")
                || productTypeName.equalsIgnoreCase("NetCDF")) {
            return new ModisSensorContext();
        } else if (productTypeName.equalsIgnoreCase("Generic Level 1B")) {
            return new SeaWiFSSensorContext();
        }
        throw new IllegalArgumentException("Invalid Product Type: " + productTypeName);
    }
}
