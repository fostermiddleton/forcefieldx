// ******************************************************************************
//
// Title:       Force Field X.
// Description: Force Field X - Software for Molecular Biophysics.
// Copyright:   Copyright (c) Michael J. Schnieders 2001-2023.
//
// This file is part of Force Field X.
//
// Force Field X is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License version 3 as published by
// the Free Software Foundation.
//
// Force Field X is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
// FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
// details.
//
// You should have received a copy of the GNU General Public License along with
// Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
// Place, Suite 330, Boston, MA 02111-1307 USA
//
// Linking this library statically or dynamically with other modules is making a
// combined work based on this library. Thus, the terms and conditions of the
// GNU General Public License cover the whole combination.
//
// As a special exception, the copyright holders of this library give you
// permission to link this library with independent modules to produce an
// executable, regardless of the license terms of these independent modules, and
// to copy and distribute the resulting executable under terms of your choice,
// provided that you also meet, for each linked independent module, the terms
// and conditions of the license of that module. An independent module is a
// module which is not derived from or based on this library. If you modify this
// library, you may extend this exception to your version of the library, but
// you are not obligated to do so. If you do not wish to do so, delete this
// exception statement from your version.
//
// ******************************************************************************
package ffx.numerics.estimator;

import ffx.utilities.Constants;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Time;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.logging.Logger;

import static ffx.numerics.estimator.EstimateBootstrapper.getBootstrapIndices;
import static ffx.numerics.estimator.Zwanzig.Directionality.BACKWARDS;
import static ffx.numerics.estimator.Zwanzig.Directionality.FORWARDS;
import static java.util.Arrays.copyOf;
import static java.util.Arrays.stream;
import static org.apache.commons.lang3.ArrayFill.fill;
import static org.apache.commons.lang3.math.NumberUtils.min;
import static org.apache.commons.math3.util.FastMath.abs;
import static org.apache.commons.math3.util.FastMath.log;
import static org.apache.commons.math3.util.FastMath.exp;

/**
 * The MultistateBennettAcceptanceRatio class defines a statistical estimator based on a generalization
 * to the Bennett Acceptance Ratio (BAR) method for multiple lambda windows. It requires an input of
 * K X N array of energies (every window at every snap at every lambda value). Two implementations exist
 * and one is commented out. The first is slower and the second is based on pymbar code.
 *
 * This class implements the method discussed in:
 *      Shirts, M. R. and Chodera, J. D. (2008) Statistically optimal analysis of samples from multiple equilibrium
 *      states. J. Chem. Phys. 129, 124105. doi:10.1063/1.2978177
 *
 * @author Matthew Speranza
 * @since 1.0
 */
public class MultistateBennettAcceptanceRatio extends SequentialEstimator implements BootstrappableEstimator {
    private static final Logger logger = Logger.getLogger(MultistateBennettAcceptanceRatio.class.getName());

    /**
     * Default BAR convergence tolerance.
     */
    private static final double DEFAULT_TOLERANCE = 1.0E-7;
    /**
     * Maximum number of MBAR iterations.
     */
    private static final int MAX_ITERS = 100;
    /**
     * Number of simulation windows.
     */
    private final int nWindows;
    /**
     * Forward Zwanzig free-energy difference estimates.
     */
    private double[] forwardZwanzig;
    /**
     * Backward Zwanzig free-energy difference estimates.
     */
    private double[] backwardZwanzig;
    /**
     * MBAR free-energy difference estimates.
     */
    private final double[] mbarEstimates;
    /**
     * MBAR free-energy difference uncertainties.
     */
    private final double[] mbarUncertainties;
    /**
     * BAR convergence tolerance.
     */
    private final double tolerance;
    /**
     * Forward Zwanzig instance.
     */
    private Zwanzig forwardsFEP;
    /**
     * Backward Zwanzig instance.
     */
    private Zwanzig backwardsFEP;
    private final Random random;
    /**
     * MBAR free-energy estimates at each lambda value.
     */
    private double[] mbarFreeEnergies;
    /**
     * Total MBAR free-energy difference estimate.
     */
    private double totalMBAREstimate;
    /**
     * Total MBAR free-energy difference uncertainty.
     */
    private double totalMBARUncertainty;
    /**
     * MBAR Enthalpy estimates
     */
    private final double[] mbarEnthalpy;
    /**
     * Alpha for BAR Enthalpy calculations
     */
    private double alpha;
    /**
     * sum for BAR Enthalpy Calculations
     */
    private double fbsum;

    private SeedType seedType;

    public enum SeedType {BAR, ZWANZIG, ZEROS}

    public MultistateBennettAcceptanceRatio(double[] lambdaValues, double[][][] energiesAll, double[] temperature) {
        this(lambdaValues, energiesAll, temperature, DEFAULT_TOLERANCE, SeedType.ZWANZIG);
    }

    public MultistateBennettAcceptanceRatio(double[] lambdaValues, double[][][] energiesAll, double[] temperature,
                                            double tolerance, SeedType seedType) {
        super(lambdaValues, energiesAll, temperature);
        this.tolerance = tolerance;
        this.seedType = seedType;
        nWindows = lambdaValues.length - 1; // Between each lambda value is a window
        // MBAR calculates free energy at each lambda value (only the differences between them have physical significance)
        mbarFreeEnergies = new double[lambdaValues.length];
        mbarEstimates = new double[nWindows];
        mbarUncertainties = new double[nWindows];
        mbarEnthalpy = new double[nWindows];
        random = new Random();

        seedEnergies();
        estimateDG();
    }

    private void seedEnergies() {
        switch (seedType) {
            case SeedType.BAR:
                SequentialEstimator barEstimator = new BennettAcceptanceRatio(lamValues, eLow, eAt, eHigh, temperatures);
                mbarFreeEnergies[0] = 0.0;
                double[] barEstimates = barEstimator.getBinEnergies();
                for (int i = 0; i < nWindows; i++) {
                    mbarFreeEnergies[i + 1] = mbarFreeEnergies[i] + barEstimates[i];
                }
                break;
            case SeedType.ZWANZIG:
                forwardsFEP = new Zwanzig(lamValues, eLow, eAt, eHigh, temperatures, FORWARDS);
                backwardsFEP = new Zwanzig(lamValues, eLow, eAt, eHigh, temperatures, BACKWARDS);
                forwardZwanzig = forwardsFEP.getBinEnergies();
                backwardZwanzig = backwardsFEP.getBinEnergies();
                mbarFreeEnergies[0] = 0.0;
                for (int i = 0; i < nWindows; i++) {
                    mbarFreeEnergies[i + 1] = mbarFreeEnergies[i] + .5*(forwardZwanzig[i] + backwardZwanzig[i]);
                }
                break;
            case SeedType.ZEROS:
                break;
            default:
                throw new IllegalArgumentException("Seed type not supported");
        }
    }

    @Override
    public void estimateDG() {
        estimateDG(false);
    }

    // Slow implementation
    /*
    @Override
    public void estimateDG(boolean randomSamples) {
        double[] prevMBAR;
        int iter = 0;

        // Precompute values for each lambda window
        double[] rtValues = new double[temperatures.length];
        double[] invRTValues = new double[temperatures.length];
        for(int i = 0; i < temperatures.length; i++){
            rtValues[i] = Constants.R * temperatures[i];
            invRTValues[i] = 1.0 / rtValues[i];
        }
        // Find the smallest length of eAllFlat array (look for INF values)
        int minSnaps = eAllFlat[0].length;

        logger.info("Using " + minSnaps + " snapshots for MBAR calculations. This is K (runs) * N (min(NumSnaps)).");
        int numSnaps = min(eAllFlat[0].length, minSnaps);
        int totalSnaps = numSnaps * nWindows;

        // Sample random snapshots from each window
        int[] indices = new int[numSnaps];
        if(randomSamples){
            indices = getBootstrapIndices(numSnaps, random);
        } else {
            for(int i = 0; i < numSnaps; i++){
                indices[i] = i;
            }
        }

        // Self-consistent iteration is used over Newton-Raphson because it is more stable & simpler to implement
        do {
            prevMBAR = copyOf(mbarFreeEnergies, mbarFreeEnergies.length);
            // Calculate new MBAR free energy estimates. Loosely based on pymbar code.
            double[] tempMBAR = new double[mbarFreeEnergies.length];
            for(int state = 0; state < mbarFreeEnergies.length; state++) { // For each lambda value
                double[] numeratorTerms = new double[numSnaps];
                double[] denominatorTerms = new double[numSnaps];
                for (int n = 0; n < numSnaps; n++) {
                    numeratorTerms[indices[n]] = -eAllFlat[state][indices[n]] * invRTValues[state];
                    double[] temp = new double[lamValues.length];
                    double[] tempB = new double[lamValues.length];
                    for (int k = 0; k < lamValues.length; k++) { // Denominator sum over K different lambda evaluations
                        temp[k] = prevMBAR[k] + (-eAllFlat[k][indices[n]] * invRTValues[k]);
                        tempB[k] = numSnaps;
                    }
                    denominatorTerms[n] = logSumExp(temp, tempB);
                }

                // Subtract all dt from all nt with a stream
                double[] temp = new double[numSnaps];
                for(int i = 0; i < numSnaps; i++){
                    temp[i] =  -denominatorTerms[i] + numeratorTerms[i];
                }
                double sum = -1.0 * logSumExp(temp);
                tempMBAR[state] = sum;
            }
            // Constrain f1=0 over the course of iterations to prevent uncontrolled growth in magnitude
            double f1 = tempMBAR[0];
            for (int i = 0; i < mbarFreeEnergies.length; i++) {
                mbarFreeEnergies[i] = tempMBAR[i] - f1;
            }
            iter++;
        } while(!converged(prevMBAR));

        for(int i = 0; i < mbarFreeEnergies.length; i++){
            mbarFreeEnergies[i] = mbarFreeEnergies[i] * rtValues[i];
        }

        logger.info(" MBAR converged after " + iter + " iterations.");
        for(int i = 0; i < nWindows; i++){
            mbarEstimates[i] = mbarFreeEnergies[i+1] - mbarFreeEnergies[i];
            logger.info(" MBAR free energy difference estimate for window " + i + " -> " + (i+1) + ": " + mbarEstimates[i]);
        }
        totalMBAREstimate = stream(mbarEstimates).sum();
        logger.info(" Total MBAR free energy difference estimate: " + totalMBAREstimate);
    }
     */

    /**
     * Implementation based on pymbar code. Precomputes values for faster calculations.
     */
    @Override
    public void estimateDG(boolean randomSamples) {
        double[] prevMBAR;
        int iter = 0;

        // Precompute values for each lambda window
        double[] rtValues = new double[temperatures.length];
        double[] invRTValues = new double[temperatures.length];
        for(int i = 0; i < temperatures.length; i++){
            rtValues[i] = Constants.R * temperatures[i];
            invRTValues[i] = 1.0 / rtValues[i];
        }
        // Find the smallest length of eAllFlat array (look for INF values)
        int minSnaps = eAllFlat[0].length;

        logger.info("Using " + minSnaps + " snapshots for MBAR calculations. This is K (runs) * N (min(NumSnaps)).");
        int numSnaps = min(eAllFlat[0].length, minSnaps);
        int totalSnaps = numSnaps * eAllFlat.length;

        // Sample random snapshots from each window
        int[] indices = new int[numSnaps];
        if(randomSamples){
            indices = getBootstrapIndices(numSnaps, random);
        } else {
            for(int i = 0; i < numSnaps; i++){
                indices[i] = i;
            }
        }

        // Self-consistent iteration is used over Newton-Raphson because it is more stable & simpler to implement
        // Calculate new MBAR free energy estimates. Based on pymbar code.
        double[][] u_kn = new double[mbarFreeEnergies.length][numSnaps];
        double[] N_k = new double[mbarFreeEnergies.length];
        for(int state = 0; state < mbarFreeEnergies.length; state++) { // For each lambda value
            for (int n = 0; n < numSnaps; n++) {
                u_kn[state][indices[n]] = eAllFlat[state][indices[n]] * invRTValues[state];
                N_k[state] = ((double) numSnaps) / totalSnaps;
            }
        }
        do {
            prevMBAR = copyOf(mbarFreeEnergies, mbarFreeEnergies.length);
            mbarFreeEnergies = selfConsistentUpdate(u_kn, N_k, mbarFreeEnergies);
            iter++;
        } while(!converged(prevMBAR));

        for(int i = 0; i < mbarFreeEnergies.length; i++){
            mbarFreeEnergies[i] = mbarFreeEnergies[i] * rtValues[i];
        }

        logger.info(" MBAR converged after " + iter + " iterations.");
        for(int i = 0; i < nWindows; i++){
            mbarEstimates[i] = mbarFreeEnergies[i+1] - mbarFreeEnergies[i];
            logger.info(" MBAR free energy difference estimate for window " + i + " -> " + (i+1) + ": " + mbarEstimates[i]);
        }
        totalMBAREstimate = stream(mbarEstimates).sum();
        logger.info(" Total MBAR free energy difference estimate: " + totalMBAREstimate);

        // TODO: Uncertainty calculations
    }

    public static double[] selfConsistentUpdate(double[][] u_kn, double[] N_k, double[] f_k) {
        int nStates = f_k.length;
        double[] updatedF_k = new double[nStates];
        double[] log_denom_n = new double[u_kn[0].length];
        double[][] logDiff = new double[u_kn.length][u_kn[0].length];
        double maxLogDiff = Double.NEGATIVE_INFINITY;
        for(int i = 0; i < u_kn[0].length; i++){
            double[] temp = new double[nStates];
            double maxTemp = Double.NEGATIVE_INFINITY;
            for(int j = 0; j < nStates; j++){
                temp[j] = f_k[j] - u_kn[j][i];
                if (temp[j] > maxTemp) {
                    maxTemp = temp[j];
                }
            }
            log_denom_n[i] = logSumExp(temp, N_k, maxTemp);
            for(int j = 0; j < nStates; j++){
                logDiff[j][i] =  - log_denom_n[i] - u_kn[j][i];
                if (logDiff[j][i] > maxLogDiff) {
                    maxLogDiff = logDiff[j][i];
                }
            }
        }

        for(int i = 0; i < nStates; i++){
            updatedF_k[i] = -1.0 * logSumExp(logDiff[i], maxLogDiff);
        }

        // Constrain f1=0 over the course of iterations to prevent uncontrolled growth in magnitude
        double norm = updatedF_k[0];
        for(int i = 0; i < nStates; i++){
            updatedF_k[i] = updatedF_k[i] - norm;
        }

        return updatedF_k;
    }
    /**
     * Calculates the log of the sum of the exponentials of the given values.
     * @param values
     * @return the sum
     */
    private static double logSumExp(double[] values, double max){
        double[] b = fill(new double[values.length], 1.0);
        return logSumExp(values, b, max);
    }

    /**
     * Calculates the log of the sum of the exponentials of the given values.
     * @param values
     * @return the sum
     */
    private static double logSumExp(double[] values, double[] b, double max) {
        // ChatGPT mostly wrote this and I tweaked it to match more closely with scipy's logsumexp implementation
        // Find the maximum value in the array.
        assert values.length == b.length: "values and b must be the same length";

        // Subtract the maximum value from each value in the array, exponentiate the result, and add up these values.
        double sum = 0.0;
        for (int i = 0; i < values.length; i++) {
            sum += b[i] * exp(values[i] - max);
        }

        // Take the natural logarithm of the sum and add the maximum value back in.
        return max + log(sum);
    }

    private boolean converged(double[] prevMBAR) {
        double[] differences = new double[prevMBAR.length];
        for(int i = 0; i < prevMBAR.length; i++){
            differences[i] = abs(prevMBAR[i] - mbarFreeEnergies[i]);
        }
        return stream(differences).allMatch(d -> d < tolerance);
    }

    @Override
    public MultistateBennettAcceptanceRatio copyEstimator() {
        return new MultistateBennettAcceptanceRatio(lamValues, eAll, temperatures, tolerance, seedType);
    }

    @Override
    public double[] getBinEnergies() {
        return mbarEstimates;
    }

    @Override
    public double[] getBinUncertainties() {
        return mbarUncertainties;
    }

    @Override
    public double getFreeEnergy() {
        return totalMBAREstimate;
    }

    @Override
    public double getUncertainty() {
        return totalMBARUncertainty;
    }

    @Override
    public int numberOfBins() {
        return nWindows;
    }

    @Override
    public double[] getBinEnthalpies() {
        return new double[0];
    }

    public static void main(String[] args) {
        long barTime = 0;
        long mbarTime = 0;
        for (int k = 0; k < 10; k++) {
            double[] lambdaValues = new double[]{0.0, 0.1};
            double[] temperatures = new double[]{298.0, 298.0};
            double[][][] energiesAll = new double[2][2][4999]; // TODO: Change here to max num snaps in BAR file
            logger.info("\nk: " + k);
            try (FileReader fr1 = new FileReader("testing/barFiles/energy_" + k + ".bar"); // TODO: Change here
                 BufferedReader br1 = new BufferedReader(fr1);) {
                String line = br1.readLine();
                String[] tokens = line.trim().split(" +");
                int numSnaps = Integer.parseInt(tokens[0]);
                double temperature = Double.parseDouble(tokens[1]);
                for (int i = 0; i < numSnaps; i++) {
                    line = br1.readLine();
                    tokens = line.trim().split(" +");
                    for (int j = 0; j < lambdaValues.length; j++) {
                        energiesAll[0][j][i] = Double.parseDouble(tokens[j + 1]);
                    }
                }
                line = br1.readLine();
                if (!line.contains("this.xyz")) {
                    throw new RuntimeException("Failed to read BAR file!");
                }
                int newSnaps = Integer.parseInt(line.trim().split(" +")[0]);
                if (newSnaps != numSnaps) {
                    logger.info("Second ensemble is shorter than first ensemble. Removing samples from first ensemble.");
                }
                for (int i = 0; i < numSnaps; i++) {
                    line = br1.readLine();
                    if (line == null) {
                        double[][][] temp = new double[2][2][newSnaps];
                        for (int j = 0; j < newSnaps; j++) {
                            temp[0][0][j] = energiesAll[0][0][j];
                            temp[0][1][j] = energiesAll[0][1][j];
                            temp[1][0][j] = energiesAll[1][0][j];
                            temp[1][1][j] = energiesAll[1][1][j];
                        }
                        energiesAll = temp;
                        break;
                    }
                    tokens = line.trim().split(" +");
                    for (int j = 0; j < lambdaValues.length; j++) {
                        energiesAll[1][j][i] = Double.parseDouble(tokens[j + 1]);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            long start = System.nanoTime();
            SequentialEstimator mbar = new MultistateBennettAcceptanceRatio(lambdaValues, energiesAll, temperatures, 1.0E-7, SeedType.ZEROS);
            mbarTime += System.nanoTime() - start;

            start = System.nanoTime();
            SequentialEstimator bar = new BennettAcceptanceRatio(lambdaValues, mbar.eLow, mbar.eAt, mbar.eHigh, temperatures);
            barTime += System.nanoTime() - start;
        }

        logger.info("BAR time in seconds: " + barTime / 1.0E9);
        logger.info("MBAR time in seconds: " + mbarTime / 1.0E9);
    }
}
