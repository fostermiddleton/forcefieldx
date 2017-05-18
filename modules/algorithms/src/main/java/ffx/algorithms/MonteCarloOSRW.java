/**
 * Title: Force Field X.
 *
 * Description: Force Field X - Software for Molecular Biophysics.
 *
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2017.
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
package ffx.algorithms;

import org.apache.commons.configuration.CompositeConfiguration;

import ffx.algorithms.Integrator.Integrators;
import ffx.algorithms.Thermostat.Thermostats;
import ffx.algorithms.mc.BoltzmannMC;
import ffx.algorithms.mc.LambdaMove;
import ffx.algorithms.mc.MDMove;
import ffx.numerics.Potential;
import ffx.potential.MolecularAssembly;
import ffx.potential.bonded.LambdaInterface;

/**
 * Sample a thermodynamic path using the OSRW method, with the time-dependent
 * bias built up using Metropolis Monte Carlo steps.
 *
 * The goal is generate coordinate (X) MC moves using molecular dynamics at a
 * fixed lambda value, following by MC lambda moves.
 *
 * 1.) At a fixed lambda, run a defined length MD trajectory to "move"
 * coordinates and dU/dL.
 *
 * 2.) Accept / Reject the MD move using the OSRW energy.
 *
 * 3.) Randomly change the value of Lambda.
 *
 * 4.) Accept / Reject the Lambda move using the OSRW energy.
 *
 * @author Mallory R. Tollefson
 */
public class MonteCarloOSRW extends BoltzmannMC {

    private final Potential potential;
    private final AbstractOSRW osrw;
    private final MolecularAssembly molecularAssembly;
    private final CompositeConfiguration properties;
    private final AlgorithmListener listener;
    private final Thermostats requestedThermostat;
    private final Integrators requestedIntegrator;
    private LambdaInterface linter;
    private double lambda = 0.0;
    private int totalMDSteps = 10000000;
    private final int mdSteps = 100;
    private final double timeStep = 1.0;
    private final double printInterval = 0.01;
    private final double temperature = 310.0;

    /**
     * @param potentialEnergy
     * @param osrw
     * @param molecularAssembly
     * @param properties
     * @param listener
     * @param requestedThermostat
     * @param requestedIntegrator
     */
    public MonteCarloOSRW(Potential potentialEnergy,
            AbstractOSRW osrw, MolecularAssembly molecularAssembly, CompositeConfiguration properties,
            AlgorithmListener listener, Thermostats requestedThermostat, Integrators requestedIntegrator) {
        this.potential = potentialEnergy;
        this.linter = (LambdaInterface) potentialEnergy;
        this.osrw = osrw;
        this.molecularAssembly = molecularAssembly;
        this.properties = properties;
        this.listener = listener;
        this.requestedThermostat = requestedThermostat;
        this.requestedIntegrator = requestedIntegrator;

        /**
         * Changing the value of lambda will be handled by this class, as well
         * as adding the time dependent bias.
         */
        osrw.setPropagateLambda(false);

    }

    public void setLambda(double lambda) {
        this.lambda = lambda;
        linter.setLambda(lambda);
    }

    public double getLambda() {
        return lambda;
    }

    /**
     * The goal is to sample coordinates (X) and converge "dU/dL" for every
     * state (lambda) along the thermodynamic path.
     *
     * 1.) At a fixed lambda, run a defined length MD trajectory to "move"
     * coordinates and dU/dL.
     *
     * 2.) Accept / Reject the MD move using the OSRW energy.
     *
     * 3.) Randomly change the value of Lambda.
     *
     * 4.) Accept / Reject the Lambda move using the OSRW energy.
     *
     * 5.) Add to the bias.
     */
    public void sample() {
        int n = potential.getNumberOfVariables();
        double[] coordinates = new double[n];
        double[] gradient = new double[n];

        int numMoves = totalMDSteps / mdSteps;

        /**
         * Initialize MC move instances.
         */
        MDMove mdMove = new MDMove(molecularAssembly, potential, properties, listener, requestedThermostat, requestedIntegrator);
        LambdaMove lambdaMove = new LambdaMove(lambda, linter);

        for (int imove = 0; imove < numMoves; imove++) {

            int lambdaBin = osrw.binForLambda(lambda);
            potential.getCoordinates(coordinates);
            osrw.energyAndGradient(coordinates, gradient);
            double currentdUdL = linter.getdEdL();
            double currentEnergy = osrw.evaluateKernel(lambdaBin, osrw.binForFLambda(currentdUdL));
            /**
             * Run MD.
             */
            mdMove.move();
            potential.getCoordinates(coordinates);
            osrw.energyAndGradient(coordinates, gradient);
            double proposeddUdL = linter.getdEdL();
            double proposedEnergy = osrw.evaluateKernel(lambdaBin, osrw.binForFLambda(proposeddUdL));
            if (evaluateMove(currentEnergy, proposedEnergy)) {
                logger.info(String.format(" Monte Carlo step accepted: e1 -> e2 %10.6f -> %10.6f", currentEnergy, proposedEnergy));
                currentEnergy = proposedEnergy;
                //Accept MD move using OSRW energy
            } else {
                mdMove.revertMove();
            }

            /**
             * Update Lambda.
             */
            potential.getCoordinates(coordinates);
            currentEnergy = osrw.energyAndGradient(coordinates, gradient);
            currentdUdL = linter.getdEdL();
            lambdaMove.move();
            proposedEnergy = osrw.energyAndGradient(coordinates, gradient);
            proposeddUdL = linter.getdEdL();
            if (evaluateMove(currentEnergy, proposedEnergy)) {
                logger.info(String.format(" Monte Carlo step accepted: e1 -> e2 %10.6f -> %10.6f", currentEnergy, proposedEnergy));
                currentdUdL = proposeddUdL;
            } else {
                lambdaMove.revertMove();
            }
            lambda = linter.getLambda();

            /**
             * Update time dependent bias.
             */
            double freeEnergy = osrw.updateFLambda(false);
            osrw.addBias(currentdUdL, freeEnergy);
        }
    }

    @Override
    protected double currentEnergy() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    protected void storeState() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void revertStep() {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
