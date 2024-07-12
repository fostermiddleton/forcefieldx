// ******************************************************************************
//
// Title:       Force Field X.
// Description: Force Field X - Software for Molecular Biophysics.
// Copyright:   Copyright (c) Michael J. Schnieders 2001-2024.
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
package ffx.numerics.fft;

import edu.rit.pj.IntegerForLoop;
import edu.rit.pj.IntegerSchedule;
import edu.rit.pj.ParallelRegion;
import edu.rit.pj.ParallelTeam;

import javax.annotation.Nullable;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.Objects.requireNonNullElseGet;

/**
 * Compute the 3D FFT of complex, double precision input of arbitrary dimensions via 1D Mixed Radix
 * FFTs in parallel.
 *
 * <p>The location of the input point [i, j, k] within the input array must be: <br>
 * double real = input[x*nextX + y*nextY + z*nextZ] <br>
 * double imag = input[x*nextX + y*nextY + z*nextZ + 1] <br>
 * where <br>
 * int nextX = 2 <br>
 * int nextY = 2*nX <br>
 * int nextZ = 2*nX*nY <br>
 *
 * @author Michal J. Schnieders
 * @see Complex
 * @since 1.0
 */
public class Complex3DParallel {

  private static final Logger logger = Logger.getLogger(Complex3DParallel.class.getName());
  private final int nX, nY, nZ;
  private final int im;
  private final int nY2, nZ2;
  private final int nextX, nextY, nextZ;
  private final double[] recip;
  private final long[] convolutionTime;
  private final int threadCount;
  private final ParallelTeam parallelTeam;
  private final Complex[] fftX;
  private final Complex[] fftY;
  private final int internalImZ;
  private final int internalNextZ;
  private final Complex[] fftZ;
  private final IntegerSchedule schedule;
  private final int nXm1, nYm1, nZm1;
  private final FFTRegion fftRegion;
  private final IFFTRegion ifftRegion;
  private final ConvolutionRegion convRegion;
  /**
   * The input array must be of size 2 * nX * nY * nZ.
   */
  public double[] input;

  /**
   * Initialize the 3D FFT for complex 3D matrix.
   *
   * @param nX           X-dimension.
   * @param nY           Y-dimension.
   * @param nZ           Z-dimension.
   * @param parallelTeam A ParallelTeam instance.
   * @since 1.0
   */
  public Complex3DParallel(int nX, int nY, int nZ, ParallelTeam parallelTeam) {
    this(nX, nY, nZ, parallelTeam, DataLayout3D.INTERLEAVED);
  }

  /**
   * Initialize the 3D FFT for complex 3D matrix.
   *
   * @param nX           X-dimension.
   * @param nY           Y-dimension.
   * @param nZ           Z-dimension.
   * @param parallelTeam A ParallelTeam instance.
   * @param dataLayout   The data layout.
   * @since 1.0
   */
  public Complex3DParallel(int nX, int nY, int nZ, ParallelTeam parallelTeam, DataLayout3D dataLayout) {
    this(nX, nY, nZ, parallelTeam, null, dataLayout);
  }

  /**
   * Initialize the 3D FFT for complex 3D matrix.
   *
   * @param nX              X-dimension.
   * @param nY              Y-dimension.
   * @param nZ              Z-dimension.
   * @param parallelTeam    A ParallelTeam instance.
   * @param integerSchedule The IntegerSchedule to use.
   * @since 1.0
   */
  public Complex3DParallel(int nX, int nY, int nZ, ParallelTeam parallelTeam, @Nullable IntegerSchedule integerSchedule) {
    this(nX, nY, nZ, parallelTeam, integerSchedule, DataLayout3D.INTERLEAVED);
  }

  /**
   * Initialize the 3D FFT for complex 3D matrix.
   *
   * @param nX              X-dimension.
   * @param nY              Y-dimension.
   * @param nZ              Z-dimension.
   * @param parallelTeam    A ParallelTeam instance.
   * @param integerSchedule The IntegerSchedule to use.
   * @param dataLayout      The data layout.
   * @since 1.0
   */
  public Complex3DParallel(int nX, int nY, int nZ, ParallelTeam parallelTeam,
                           @Nullable IntegerSchedule integerSchedule, DataLayout3D dataLayout) {
    this.nX = nX;
    this.nY = nY;
    this.nZ = nZ;
    this.parallelTeam = parallelTeam;
    recip = new double[nX * nY * nZ];
    nY2 = 2 * this.nY;
    nZ2 = 2 * this.nZ;

    DataLayout1D dataLayout1D;
    switch (dataLayout) {
      default:
      case INTERLEAVED:
        // Interleaved data layout.
        im = 1;
        nextX = 2;
        nextY = 2 * nX;
        nextZ = 2 * nX * nY;
        // Internal 1D FFTs will be performed in interleaved format.
        dataLayout1D = DataLayout1D.INTERLEAVED;
        // Transforms along the Z-axis will be repacked into 1D interleaved format.
        internalImZ = 1;
        internalNextZ = 2;
        break;
      case BLOCKED_X:
        // Blocking is along the X-axis.
        im = nX;
        nextX = 1;
        nextY = 2 * nX;
        nextZ = 2 * nX * nY;
        // Internal 1D FFTs will be performed in blocked format.
        dataLayout1D = DataLayout1D.BLOCKED;
        // Transforms along the Z-axis will be repacked into 1D blocked format.
        internalNextZ = 1;
        internalImZ = nZ;
        break;
      case BLOCKED_XY:
        // Blocking is based on 2D XY-planes.
        im = nX * nY;
        nextX = 1;
        nextY = nX;
        nextZ = 2 * nY * nX;
        // Internal 1D FFTs will be performed in blocked format.
        dataLayout1D = DataLayout1D.BLOCKED;
        // Transforms along the Z-axis will be repacked into 1D blocked format.
        internalNextZ = 1;
        internalImZ = nZ;
        break;
      case BLOCKED_XYZ:
        // Blocking is based on 3D XYZ-volume with all real values followed by all imaginary.
        im = nX * nY * nZ;
        nextX = 1;
        nextY = nX;
        nextZ = nY * nX;
        // Internal 1D FFTs will be performed in blocked format.
        dataLayout1D = DataLayout1D.BLOCKED;
        // Transforms along the Z-axis will be repacked into 1D blocked format.
        internalNextZ = 1;
        internalImZ = nZ;
        break;
    }

    nXm1 = this.nX - 1;
    nYm1 = this.nY - 1;
    nZm1 = this.nZ - 1;
    threadCount = parallelTeam.getThreadCount();
    schedule = requireNonNullElseGet(integerSchedule, IntegerSchedule::fixed);
    fftX = new Complex[threadCount];
    fftY = new Complex[threadCount];
    fftZ = new Complex[threadCount];
    // Initialize the FFTs for each dimension independently to take advantage of Twiddle factor caching.
    for (int i = 0; i < threadCount; i++) {
      fftX[i] = new Complex(nX, dataLayout1D, im);
    }
    for (int i = 0; i < threadCount; i++) {
      if (nX != nY) {
        fftY[i] = new Complex(nY, dataLayout1D, im);
      } else {
        // If nX == nY, then the 1D FFTs are the same and we can reuse them.
        fftY[i] = fftX[i];
      }
    }
    for (int i = 0; i < threadCount; i++) {
      fftZ[i] = new Complex(nZ, dataLayout1D, internalImZ);
    }
    fftRegion = new FFTRegion();
    ifftRegion = new IFFTRegion();
    convRegion = new ConvolutionRegion();
    convolutionTime = new long[threadCount];
  }

  /**
   * Compute the 3D FFT, perform a multiplication in reciprocal space,
   * and the inverse 3D FFT in parallel.
   *
   * @param input The input array must be of size 2 * nX * nY * nZ.
   * @since 1.0
   */
  public void convolution(final double[] input) {
    this.input = input;
    try {
      parallelTeam.execute(convRegion);
    } catch (Exception e) {
      String message = "Fatal exception evaluating a convolution.\n";
      logger.log(Level.SEVERE, message, e);
    }
  }

  /**
   * Compute the 3D FFT in parallel.
   *
   * @param input The input array must be of size 2 * nX * nY * nZ.
   * @since 1.0
   */
  public void fft(final double[] input) {
    this.input = input;
    try {
      parallelTeam.execute(fftRegion);
    } catch (Exception e) {
      String message = " Fatal exception evaluating the FFT.\n";
      logger.log(Level.SEVERE, message, e);
    }
  }

  /**
   * Get the timings for each thread.
   *
   * @return The timings for each thread.
   */
  public long[] getTimings() {
    return convolutionTime;
  }

  /**
   * Compute the inverse 3D FFT in parallel.
   *
   * @param input The input array must be of size 2 * nX * nY * nZ.
   * @since 1.0
   */
  public void ifft(final double[] input) {
    this.input = input;
    try {
      parallelTeam.execute(ifftRegion);
    } catch (Exception e) {
      String message = "Fatal exception evaluating the inverse FFT.\n";
      logger.log(Level.SEVERE, message, e);
      System.exit(-1);
    }
  }

  /**
   * Initialize the timing array.
   */
  public void initTiming() {
    for (int i = 0; i < threadCount; i++) {
      convolutionTime[i] = 0;
    }
  }

  /**
   * Setter for the field <code>recip</code>.
   *
   * @param recip an array of double.
   */
  public void setRecip(double[] recip) {
    int offset, y, x, z, i;

    // Reorder the reciprocal space data into the order it is needed by the convolution routine.
    int index = 0;
    for (offset = 0, y = 0; y < nY; y++) {
      for (x = 0; x < nX; x++, offset += 1) {
        for (i = 0, z = offset; i < nZ; i++, z += nX * nY) {
          this.recip[index++] = recip[z];
        }
      }
    }
  }

  /**
   * An external ParallelRegion can be used as follows: <code>
   * start() {
   * fftRegion.input = input;
   * }
   * run(){
   * execute(0, nZm1, fftRegion.fftXYLoop[threadID]);
   * execute(0, nXm1, fftRegion.fftZLoop[threadID]);
   * }
   * </code>
   */
  private class FFTRegion extends ParallelRegion {

    private final FFTXYLoop[] fftXYLoop;
    private final FFTZLoop[] fftZLoop;

    private FFTRegion() {
      fftXYLoop = new FFTXYLoop[threadCount];
      fftZLoop = new FFTZLoop[threadCount];
      for (int i = 0; i < threadCount; i++) {
        fftXYLoop[i] = new FFTXYLoop();
        fftZLoop[i] = new FFTZLoop();
      }
    }

    @Override
    public void run() {
      int threadIndex = getThreadIndex();
      try {
        execute(0, nZm1, fftXYLoop[threadIndex]);
        execute(0, nXm1, fftZLoop[threadIndex]);
      } catch (Exception e) {
        logger.severe(e.toString());
      }
    }
  }

  /**
   * An external ParallelRegion can be used as follows: <code>
   * start() {
   * ifftRegion.input = input;
   * }
   * run(){
   * execute(0, nXm1, ifftRegion.ifftZLoop[threadID]);
   * execute(0, nZm1, ifftRegion.ifftXYLoop[threadID]);
   * }
   * </code>
   */
  private class IFFTRegion extends ParallelRegion {

    private final IFFTXYLoop[] ifftXYLoop;
    private final IFFTZLoop[] ifftZLoop;

    private IFFTRegion() {
      ifftXYLoop = new IFFTXYLoop[threadCount];
      ifftZLoop = new IFFTZLoop[threadCount];
      for (int i = 0; i < threadCount; i++) {
        ifftXYLoop[i] = new IFFTXYLoop();
        ifftZLoop[i] = new IFFTZLoop();
      }
    }

    @Override
    public void run() {
      int threadIndex = getThreadIndex();
      try {
        execute(0, nXm1, ifftZLoop[threadIndex]);
        execute(0, nZm1, ifftXYLoop[threadIndex]);
      } catch (Exception e) {
        logger.severe(e.toString());
      }
    }
  }

  /**
   * An external ParallelRegion can be used as follows: <code>
   * start() {
   * convRegion.input = input;
   * }
   * run(){
   * execute(0, nZm1, convRegion.fftXYLoop[threadID]);
   * execute(0, nYm1, convRegion.fftZIZLoop[threadID]);
   * execute(0, nZm1, convRegion.ifftXYLoop[threadID]);
   * }
   * </code>
   */
  private class ConvolutionRegion extends ParallelRegion {

    private final FFTXYLoop[] fftXYLoop;
    private final FFTZIZLoop[] fftZIZLoop;
    private final IFFTXYLoop[] ifftXYLoop;

    private ConvolutionRegion() {
      fftXYLoop = new FFTXYLoop[threadCount];
      fftZIZLoop = new FFTZIZLoop[threadCount];
      ifftXYLoop = new IFFTXYLoop[threadCount];
      for (int i = 0; i < threadCount; i++) {
        fftXYLoop[i] = new FFTXYLoop();
        fftZIZLoop[i] = new FFTZIZLoop();
        ifftXYLoop[i] = new IFFTXYLoop();
      }
    }

    @Override
    public void run() {
      int threadIndex = getThreadIndex();
      convolutionTime[threadIndex] -= System.nanoTime();
      try {
        execute(0, nZm1, fftXYLoop[threadIndex]);
        execute(0, nYm1, fftZIZLoop[threadIndex]);
        execute(0, nZm1, ifftXYLoop[threadIndex]);
      } catch (Exception e) {
        logger.severe(e.toString());
      }
      convolutionTime[threadIndex] += System.nanoTime();
    }
  }

  private class FFTXYLoop extends IntegerForLoop {

    private Complex localFFTX;
    private Complex localFFTY;

    @Override
    public void run(final int lb, final int ub) {
      for (int z = lb; z <= ub; z++) {
        for (int offset = z * nextZ, y = 0; y < nY; y++, offset += nextY) {
          localFFTX.fft(input, offset, nextX);
        }
        for (int offset = z * nextZ, x = 0; x < nX; x++, offset += nextX) {
          localFFTY.fft(input, offset, nextY);
        }
      }
    }

    @Override
    public IntegerSchedule schedule() {
      return schedule;
    }

    @Override
    public void start() {
      localFFTX = fftX[getThreadIndex()];
      localFFTY = fftY[getThreadIndex()];
    }
  }

  private class FFTZLoop extends IntegerForLoop {
    private final double[] work;
    private Complex localFFTZ;

    private FFTZLoop() {
      work = new double[nZ2];
    }

    @Override
    public void run(final int lb, final int ub) {
      for (int x = lb, offset = lb * nY2; x <= ub; x++) {
        for (int y = 0; y < nY; y++, offset += 2) {
          for (int i = 0, z = offset; i < nZ; i++, z += nextZ) {
            int w = i * internalNextZ;
            work[w] = input[z];
            work[w + internalImZ] = input[z + im];
          }
          localFFTZ.fft(work, 0, internalNextZ);
          for (int i = 0, z = offset; i < nZ; i++, z += nextZ) {
            int w = i * internalNextZ;
            input[z] = work[w];
            input[z + im] = work[w + internalImZ];
          }
        }
      }
    }

    @Override
    public IntegerSchedule schedule() {
      return schedule;
    }

    @Override
    public void start() {
      localFFTZ = fftZ[getThreadIndex()];
    }
  }

  private class IFFTXYLoop extends IntegerForLoop {

    private Complex localFFTY;
    private Complex localFFTX;

    @Override
    public void run(final int lb, final int ub) {
      for (int z = lb; z <= ub; z++) {
        for (int offset = z * nextZ, x = 0; x < nX; x++, offset += nextX) {
          localFFTY.ifft(input, offset, nextY);
        }
        for (int offset = z * nextZ, y = 0; y < nY; y++, offset += nextY) {
          localFFTX.ifft(input, offset, nextX);
        }
      }
    }

    @Override
    public IntegerSchedule schedule() {
      return schedule;
    }

    @Override
    public void start() {
      localFFTX = fftX[getThreadIndex()];
      localFFTY = fftY[getThreadIndex()];
    }
  }

  private class IFFTZLoop extends IntegerForLoop {

    private final double[] work;
    private Complex localFFTZ;

    private IFFTZLoop() {
      work = new double[nZ2];
    }

    @Override
    public void run(final int lb, final int ub) {
      for (int offset = lb * nY2, x = lb; x <= ub; x++) {
        for (int y = 0; y < nY; y++, offset += 2) {
          for (int i = 0, z = offset; i < nZ; i++, z += nextZ) {
            int w = i * internalNextZ;
            work[w] = input[z];
            work[w + internalImZ] = input[z + im];
          }
          localFFTZ.ifft(work, 0, 2);
          for (int i = 0, z = offset; i < nZ; i++, z += nextZ) {
            int w = i * internalNextZ;
            input[z] = work[w];
            input[z + im] = work[w + internalImZ];
          }
        }
      }
    }

    @Override
    public IntegerSchedule schedule() {
      return schedule;
    }

    @Override
    public void start() {
      localFFTZ = fftZ[getThreadIndex()];
    }
  }

  private class FFTZIZLoop extends IntegerForLoop {

    private final double[] work;
    private Complex localFFTZ;

    private FFTZIZLoop() {
      work = new double[nZ2];
    }

    @Override
    public void run(final int lb, final int ub) {
      int index = nX * nZ * lb;
      for (int offset = lb * nextY, y = lb; y <= ub; y++) {
        for (int x = 0; x < nX; x++, offset += 2) {
          for (int i = 0, z = offset; i < nZ; i++, z += nextZ) {
            int w = i * internalNextZ;
            work[w] = input[z];
            work[w + internalImZ] = input[z + im];
          }
          localFFTZ.fft(work, 0, internalNextZ);
          for (int i = 0; i < nZ; i++) {
            double r = recip[index++];
            int w = i * internalNextZ;
            work[w] *= r;
            work[w + internalImZ] *= r;
          }
          localFFTZ.ifft(work, 0, internalNextZ);
          for (int i = 0, z = offset; i < nZ; i++, z += nextZ) {
            int w = i * internalNextZ;
            input[z] = work[w];
            input[z + im] = work[w + internalImZ];
          }
        }
      }
    }

    @Override
    public IntegerSchedule schedule() {
      return schedule;
    }

    @Override
    public void start() {
      localFFTZ = fftZ[getThreadIndex()];
    }
  }

  /**
   * Initialize a 3D data for testing purposes.
   *
   * @param dim The dimension of the cube.
   * @since 1.0
   */
  public static double[] initRandomData(int dim, ParallelTeam parallelTeam) {
    int n = dim * dim * dim;
    double[] data = new double[2 * n];
    try {
      parallelTeam.execute(
          new ParallelRegion() {
            @Override
            public void run() {
              try {
                execute(
                    0,
                    dim - 1,
                    new IntegerForLoop() {
                      @Override
                      public void run(final int lb, final int ub) {
                        Random randomNumberGenerator = new Random(1);
                        int index = dim * dim * lb * 2;
                        for (int i = lb; i <= ub; i++) {
                          for (int j = 0; j < dim; j++) {
                            for (int k = 0; k < dim; k++) {
                              double randomNumber = randomNumberGenerator.nextDouble();
                              data[index] = randomNumber;
                              index += 2;
                            }
                          }
                        }
                      }
                    });
              } catch (Exception e) {
                System.out.println(e.getMessage());
                System.exit(-1);
              }
            }
          });
    } catch (Exception e) {
      System.out.println(e.getMessage());
      System.exit(-1);
    }
    return data;
  }

  /**
   * Test the Complex3DParallel FFT.
   *
   * @param args an array of {@link java.lang.String} objects.
   * @throws java.lang.Exception if any.
   * @since 1.0
   */
  public static void main(String[] args) throws Exception {
    int dimNotFinal = 128;
    int nCPU = ParallelTeam.getDefaultThreadCount();
    int reps = 5;
    try {
      dimNotFinal = Integer.parseInt(args[0]);
      if (dimNotFinal < 1) {
        dimNotFinal = 100;
      }
      nCPU = Integer.parseInt(args[1]);
      if (nCPU < 1) {
        nCPU = ParallelTeam.getDefaultThreadCount();
      }
      reps = Integer.parseInt(args[2]);
      if (reps < 1) {
        reps = 5;
      }
    } catch (Exception e) {
      //
    }
    final int dim = dimNotFinal;
    System.out.printf("Initializing a %d cubed grid for %d CPUs.\n"
            + "The best timing out of %d repetitions will be used.%n",
        dim, nCPU, reps);
    // One dimension of the serial array divided by the number of threads.
    Complex3D complexDoubleFFT3D = new Complex3D(dim, dim, dim);
    ParallelTeam parallelTeam = new ParallelTeam(nCPU);
    Complex3DParallel parallelComplexDoubleFFT3D =
        new Complex3DParallel(dim, dim, dim, parallelTeam);
    final int dimCubed = dim * dim * dim;
    final double[] data = initRandomData(dim, parallelTeam);
    final double[] work = new double[dimCubed * 2];

    double toSeconds = 0.000000001;
    long seqTime = Long.MAX_VALUE;
    long parTime = Long.MAX_VALUE;
    long seqTimeConv = Long.MAX_VALUE;
    long parTimeConv = Long.MAX_VALUE;

    complexDoubleFFT3D.setRecip(work);
    parallelComplexDoubleFFT3D.setRecip(work);

    // Warm-up
    System.out.println("Warm Up Sequential FFT");
    complexDoubleFFT3D.fft(data);
    System.out.println("Warm Up Sequential IFFT");
    complexDoubleFFT3D.ifft(data);
    System.out.println("Warm Up Sequential Convolution");
    complexDoubleFFT3D.convolution(data);

    for (int i = 0; i < reps; i++) {
      System.out.printf(" Iteration %d%n", i + 1);
      long time = System.nanoTime();
      complexDoubleFFT3D.fft(data);
      complexDoubleFFT3D.ifft(data);
      time = (System.nanoTime() - time);
      System.out.printf("  Sequential FFT:  %9.6f (sec)%n", toSeconds * time);
      if (time < seqTime) {
        seqTime = time;
      }
      time = System.nanoTime();
      complexDoubleFFT3D.convolution(data);
      time = (System.nanoTime() - time);
      System.out.printf("  Sequential Conv: %9.6f (sec)%n", toSeconds * time);
      if (time < seqTimeConv) {
        seqTimeConv = time;
      }
    }

    // Warm-up
    System.out.println("Warm up Parallel FFT");
    parallelComplexDoubleFFT3D.fft(data);
    System.out.println("Warm up Parallel IFFT");
    parallelComplexDoubleFFT3D.ifft(data);
    System.out.println("Warm up Parallel Convolution");
    parallelComplexDoubleFFT3D.convolution(data);

    for (int i = 0; i < reps; i++) {
      System.out.printf(" Iteration %d%n", i + 1);
      long time = System.nanoTime();
      parallelComplexDoubleFFT3D.fft(data);
      parallelComplexDoubleFFT3D.ifft(data);
      time = (System.nanoTime() - time);
      System.out.printf("  Parallel FFT:  %9.6f (sec)%n", toSeconds * time);
      if (time < parTime) {
        parTime = time;
      }

      time = System.nanoTime();
      parallelComplexDoubleFFT3D.convolution(data);
      time = (System.nanoTime() - time);
      System.out.printf("  Parallel Conv: %9.6f (sec)%n", toSeconds * time);
      if (time < parTimeConv) {
        parTimeConv = time;
      }
    }
    System.out.printf(" Best Sequential FFT Time:   %9.6f (sec)%n", toSeconds * seqTime);
    System.out.printf(" Best Sequential Conv. Time: %9.6f (sec)%n", toSeconds * seqTimeConv);
    System.out.printf(" Best Parallel FFT Time:     %9.6f (sec)%n", toSeconds * parTime);
    System.out.printf(" Best Parallel Conv. Time:   %9.6f (sec)%n", toSeconds * parTimeConv);
    System.out.printf(" 3D FFT Speedup:             %9.6f X%n", (double) seqTime / parTime);
    System.out.printf(" 3D Conv Speedup:            %9.6f X%n", (double) seqTimeConv / parTimeConv);

    parallelTeam.shutdown();
  }
}
