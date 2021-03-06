/* 
 * Copyright (C) 2002-2008 by Brockmann Consult
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation. This program is distributed in the hope it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.esa.beam.idepix.ui.actions;

import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.ui.command.CommandEvent;
import org.esa.beam.idepix.algorithms.avhrrac.AvhrrAcOp;
import org.esa.beam.idepix.ui.IdepixDefaultDialog;
import org.esa.beam.visat.actions.AbstractVisatAction;

/**
 * Idepix action for GlobAlbedo algorithm.
 *
 * @author Olaf Danne
 */
public class IdepixAvhrrAcAction extends AbstractVisatAction {
    @Override
    public void actionPerformed(CommandEvent commandEvent) {
        final IdepixDefaultDialog dialog =
            new IdepixDefaultDialog(OperatorSpi.getOperatorAlias(AvhrrAcOp.class),
                    getAppContext(),
                    "IDEPIX Pixel Identification Tool - LandCover AVHRR Algorithm",
                    "idepixChain","");
        dialog.show();
    }

}
