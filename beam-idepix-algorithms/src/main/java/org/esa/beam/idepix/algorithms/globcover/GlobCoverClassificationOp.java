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

package org.esa.beam.idepix.algorithms.globcover;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.pointop.ProductConfigurer;
import org.esa.beam.framework.gpf.pointop.Sample;
import org.esa.beam.framework.gpf.pointop.SampleConfigurer;
import org.esa.beam.framework.gpf.pointop.SampleOperator;
import org.esa.beam.framework.gpf.pointop.WritableSample;
import org.esa.beam.idepix.IdepixConstants;
import org.esa.beam.idepix.util.IdepixUtils;
import org.esa.beam.meris.cloud.CombinedCloudOp;
import org.esa.beam.meris.l2auxdata.Constants;
import org.esa.beam.util.BitSetter;

/**
 * Operator for adapting the output of hte GlobCover chain to
 * the Idepix GlobAlbedo output
 *
 * @author MarcoZ
 */
@OperatorMetadata(alias = "idepix.globcover.classification",
                  version = "2.2",
                  internal = true,
                  authors = "Marco Zuehlke",
                  copyright = "(c) 2012 by Brockmann Consult",
                  description = "Adapts the output of the globcover chain to idepix.")
public class GlobCoverClassificationOp extends SampleOperator {

    @SourceProduct
    private Product cloudProduct;
    @SourceProduct
    private Product brrProduct;

    @Override
    protected void configureTargetProduct(ProductConfigurer productConfigurer) {
        super.configureTargetProduct(productConfigurer);

        Product target = productConfigurer.getTargetProduct();
        target.setName(brrProduct.getName());
        target.setProductType(brrProduct.getProductType());

        Band flagBand = productConfigurer.addBand(IdepixUtils.IDEPIX_CLOUD_FLAGS, ProductData.TYPE_INT16);
        FlagCoding flagCoding = IdepixUtils.createIdepixFlagCoding(IdepixUtils.IDEPIX_CLOUD_FLAGS);
        flagBand.setSampleCoding(flagCoding);
        target.getFlagCodingGroup().add(flagCoding);
        IdepixUtils.setupIdepixCloudscreeningBitmasks(target);
    }

    @Override
    protected void configureSourceSamples(SampleConfigurer sampleConfigurer) throws OperatorException {
        sampleConfigurer.defineSample(0, CombinedCloudOp.FLAG_BAND_NAME, cloudProduct);
        sampleConfigurer.defineSample(1, "l2_flags_p1", brrProduct);
    }

    @Override
    protected void configureTargetSamples(SampleConfigurer sampleConfigurer) throws OperatorException {
        sampleConfigurer.defineSample(0, IdepixUtils.IDEPIX_CLOUD_FLAGS);
    }

    @Override
    protected void computeSample(int x, int y, Sample[] sourceSamples, WritableSample targetSample) {
        int cloudFlag = sourceSamples[0].getInt();
        boolean clear = BitSetter.isFlagSet(cloudFlag, 0);
        boolean cloud = BitSetter.isFlagSet(cloudFlag, 1);
        boolean snow = BitSetter.isFlagSet(cloudFlag, 2);
        boolean cloudEdge = BitSetter.isFlagSet(cloudFlag, 3);
        boolean cloudShadow = BitSetter.isFlagSet(cloudFlag, 4);

        boolean land = BitSetter.isFlagSet(sourceSamples[1].getInt(), Constants.F_LANDCONS);

        int resultFlag = 0;
        resultFlag = BitSetter.setFlag(resultFlag, IdepixConstants.F_CLOUD, cloud);
        resultFlag = BitSetter.setFlag(resultFlag, IdepixConstants.F_CLOUD_BUFFER, cloudEdge);
        resultFlag = BitSetter.setFlag(resultFlag, IdepixConstants.F_CLOUD_SHADOW, cloudShadow);
        resultFlag = BitSetter.setFlag(resultFlag, IdepixConstants.F_CLEAR_LAND, clear && land);
        resultFlag = BitSetter.setFlag(resultFlag, IdepixConstants.F_CLEAR_WATER, clear && !land);
        resultFlag = BitSetter.setFlag(resultFlag, IdepixConstants.F_CLEAR_SNOW, snow && !cloud && !cloudEdge && !cloudShadow);
        resultFlag = BitSetter.setFlag(resultFlag, IdepixConstants.F_LAND, land);
        resultFlag = BitSetter.setFlag(resultFlag, IdepixConstants.F_WATER, !land);

        targetSample.set(resultFlag);
    }

    /**
     * The Service Provider Interface (SPI) for the operator.
     * It provides operator meta-data and is a factory for new operator instances.
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(GlobCoverClassificationOp.class);
        }
    }

}
