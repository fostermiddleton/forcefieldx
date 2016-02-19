/**
 * Title: Force Field X.
 *
 * Description: Force Field X - Software for Molecular Biophysics.
 *
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2016.
 *
 * This file is part of Force Field X.
 *
 * Force Field X is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 as published by
 * the Free Software Foundation.
 *
 * Force Field X is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place, Suite 330, Boston, MA 02111-1307 USA
 *
 * Linking this library statically or dynamically with other modules is making a
 * combined work based on this library. Thus, the terms and conditions of the
 * GNU General Public License cover the whole combination.
 *
 * As a special exception, the copyright holders of this library give you
 * permission to link this library with independent modules to produce an
 * executable, regardless of the license terms of these independent modules, and
 * to copy and distribute the resulting executable under terms of your choice,
 * provided that you also meet, for each linked independent module, the terms
 * and conditions of the license of that module. An independent module is a
 * module which is not derived from or based on this library. If you modify this
 * library, you may extend this exception to your version of the library, but
 * you are not obligated to do so. If you do not wish to do so, delete this
 * exception statement from your version.
 */
package ffx.algorithms.mc;

import ffx.potential.MolecularAssembly;
import ffx.potential.bonded.ROLS;
import ffx.potential.bonded.Residue;
import ffx.potential.bonded.ResidueEnumerations.AminoAcid3;
import ffx.potential.bonded.ResidueState;
import ffx.potential.bonded.Rotamer;
import ffx.potential.bonded.RotamerLibrary;
import ffx.potential.bonded.Torsion;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Represents a random chi[0] spin of the target residue.
 * For use with RosenbluthRotamerMC.
 * @author S. LuCore
 */
public class RosenbluthChiAllMove implements MCMove {
    private static final Logger logger = Logger.getLogger(RosenbluthChi0Move.class.getName());
    
    private final Residue target;
    private final ResidueState origState;
    private final Rotamer newState;
    public final double thetas[];
    
    public RosenbluthChiAllMove(Residue target) {
        this.target = target;
        AminoAcid3 name = AminoAcid3.valueOf(target.getName());
        origState = target.storeState();
        double chi[] = RotamerLibrary.measureRotamer(target, false);
        thetas = new double[chi.length];
        for (int i = 0; i < thetas.length; i++) {
            thetas[i] = ThreadLocalRandom.current().nextDouble(360.0) - 180;
            chi[i] = thetas[i];
        }
        // Need to add sigma values to construct a new Rotamer with these chis.
        double values[] = new double[chi.length * 2];
        for (int i = 0; i < chi.length; i++) {
            int ii = 2*i;
            values[ii] = chi[i];
            values[ii+1] = 0.0;
        }
        newState = new Rotamer(name, values);
    }
    
    /**
     * Performs the move associated with this MCMove.
     * Also calls energy() on all Torsions to update chi values.
     * @return Extra-potential energy changes
     */
    @Override
    public double move() {
        RotamerLibrary.applyRotamer(target, newState);
        updateTorsions();
        return 0.0;
    }
    
    /**
     * Reverts the last applied move() call. Returns the same energy change as
     * described above (with the same sign).
     * @return Extra-potential energy changes
     */
    @Override
    public double revertMove() {
        target.revertState(origState);
        updateTorsions();
        return 0.0;
    }
    
    private void updateTorsions() {
        List<ROLS> torsions = target.getTorsionList();
        for (ROLS rols : torsions) {
            ((Torsion) rols).update();
        }
    }
    
    /**
     * Returns the extra-potential energy change from the last move() call.
     * @return Extra-potential energy changes
     */
    @Override
    public double getEcorrection() {
        return 0.0;
    }
    
    /**
     * Returns a description of the MCMove.
     * @return 
     */
    @Override
    public String getDescription() {
        return String.format("Rosenbluth Rotamer Move:\n   Res:   %s\n   Thetas: %s",
                target.toString(), Arrays.toString(thetas));
    }
    
}
