package org.esa.beam.idepix.ui;

import com.bc.ceres.swing.selection.SelectionChangeEvent;
import com.bc.ceres.swing.selection.SelectionChangeListener;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.ui.TargetProductSelectorModel;
import org.esa.beam.idepix.operators.MepixConstants;
import org.esa.beam.idepix.util.MepixUtils;

/**
 * Listener for selection of source product
 *
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
class SourceProductSelectionListener implements SelectionChangeListener {

    private MepixForm form;
    private TargetProductSelectorModel targetProductSelectorModel;
    private String targetProductNameSuffix;

    SourceProductSelectionListener(MepixForm form, TargetProductSelectorModel targetProductSelectorModel,
                                          String targetProductNameSuffix) {
        this.form = form;
        this.targetProductSelectorModel = targetProductSelectorModel;
        this.targetProductNameSuffix = targetProductNameSuffix;
    }

    @Override
    public void selectionChanged(SelectionChangeEvent event) {
        final Product selectedProduct = (Product) event.getSelection().getSelectedValue();

        if (selectedProduct != null) {
            // convert to MEPIX specific product name
            String mepixName = selectedProduct.getName();

            // check for MERIS:
            if (MepixUtils.isValidMerisProduct(selectedProduct)) {
                String resName = "";
                if (selectedProduct.getProductType().equals(EnvisatConstants.MERIS_RR_L1B_PRODUCT_TYPE_NAME)) {
                    // MER_RR__1
                    resName = EnvisatConstants.MERIS_RR_L1B_PRODUCT_TYPE_NAME;
                } else if (selectedProduct.getProductType().equals(
                        EnvisatConstants.MERIS_FR_L1B_PRODUCT_TYPE_NAME)) {
                    // MER_FR__1
                    resName = EnvisatConstants.MERIS_FR_L1B_PRODUCT_TYPE_NAME;
                }
                final String nameL1bPrefix = resName.substring(0, resName.length() - 2);
                final int nameLength = mepixName.length();
                mepixName = nameL1bPrefix + "2MEPIX" + mepixName.substring(nameL1bPrefix.length() + 6,
                                                                           nameLength);
                if (mepixName.toUpperCase().endsWith(".N1") || mepixName.toUpperCase().endsWith(".E1") ||
                    mepixName.toUpperCase().endsWith(".E2")) {
                    mepixName = mepixName.substring(0, mepixName.length() - 3);
                }
                targetProductSelectorModel.setProductName(mepixName + targetProductNameSuffix);
                form.setEnabledAt(MepixConstants.GLOBALBEDO_TAB_INDEX, true);
                form.setEnabledAt(MepixConstants.IPF_TAB_INDEX, true);
                form.setEnabledAt(MepixConstants.PRESSURE_TAB_INDEX, true);
                form.setEnabledAt(MepixConstants.CLOUDS_TAB_INDEX, true);
                form.setEnabledAt(MepixConstants.COASTCOLOUR_TAB_INDEX, false);
            } else if (MepixUtils.isValidAatsrProduct(selectedProduct)) {
                // check for AATSR:
                mepixName = selectedProduct.getName() + "VGT2MEPIX";  // todo: discuss
                targetProductSelectorModel.setProductName(mepixName);
                form.setEnabledAt(MepixConstants.GLOBALBEDO_TAB_INDEX, true);
                form.setEnabledAt(MepixConstants.IPF_TAB_INDEX, false);
                form.setEnabledAt(MepixConstants.PRESSURE_TAB_INDEX, false);
                form.setEnabledAt(MepixConstants.CLOUDS_TAB_INDEX, false);
                form.setEnabledAt(MepixConstants.COASTCOLOUR_TAB_INDEX, false);
            } else if (MepixUtils.isValidVgtProduct(selectedProduct)) {
                // check for VGT:
                mepixName = selectedProduct.getName() + "VGT2MEPIX";  // todo: discuss
                targetProductSelectorModel.setProductName(mepixName);
                form.setEnabledAt(MepixConstants.GLOBALBEDO_TAB_INDEX, true);
                form.setEnabledAt(MepixConstants.IPF_TAB_INDEX, false);
                form.setEnabledAt(MepixConstants.PRESSURE_TAB_INDEX, false);
                form.setEnabledAt(MepixConstants.CLOUDS_TAB_INDEX, false);
                form.setEnabledAt(MepixConstants.COASTCOLOUR_TAB_INDEX, false);
            }
        }
    }

    @Override
    public void selectionContextChanged(SelectionChangeEvent event) {
        // no actions
    }

}
