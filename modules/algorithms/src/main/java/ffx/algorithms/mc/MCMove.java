/**
 * Title: Force Field X.
 *
 * Description: Force Field X - Software for Molecular Biophysics.
 *
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2015.
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

/**
 * The MCMove interface defines the basic functionality of a Monte Carlo move; 
 * that it can apply its move and revert it.
 *
 * @author Michael J. Schnieders
 * @author Jacob M. Litman
 * @since 1.0
 *
 */
public interface MCMove {
    
    /**
     * Performs the move associated with this MCMove. Also returns extra-potential
     * energy changes (any change in energy not associated with the underlying
     * Potential). One example thereof is the pH term in titration Monte Carlo 
     * steps, which does not come out in the underlying ForceFieldEnergy.
     * @return Extra-potential energy changes
     */
    public double move();
    
    /**
     * Reverts the last applied move() call. Returns the same energy change as
     * described above (with the same sign).
     * @return Extra-potential energy changes
     */
    public double revertMove();
    
    /**
     * Returns the extra-potential energy change from the last move() call.
     * @return Extra-potential energy changes
     */
    public double getEcorrection();
    
    /**
     * Returns a description of the MCMove.
     * @return 
     */
    public String getDescription();
}