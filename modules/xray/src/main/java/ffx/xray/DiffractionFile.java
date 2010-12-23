/**
 * Title: Force Field X
 * Description: Force Field X - Software for Molecular Biophysics.
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2009
 *
 * This file is part of Force Field X.
 *
 * Force Field X is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as published
 * by the Free Software Foundation.
 *
 * Force Field X is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Force Field X; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307  USA
 */
package ffx.xray;

import static org.apache.commons.io.FilenameUtils.isExtension;
import static org.apache.commons.io.FilenameUtils.removeExtension;

import ffx.potential.bonded.MolecularAssembly;
import java.io.File;
import java.util.logging.Logger;

/**
 *
 * @author Tim Fenn
 */
public class DiffractionFile {

    private static final Logger logger = Logger.getLogger(DiffractionFile.class.getName());
    protected final String filename;
    protected final double weight;
    protected final boolean neutron;
    protected final DiffractionFileFilter diffractionfilter;

    public DiffractionFile(String filename) {
        this(filename, 1.0, false);
    }

    public DiffractionFile(String filename, double weight) {
        this(filename, weight, false);
    }

    public DiffractionFile(String filename, double weight, boolean neutron) {
        File tmp = new File(filename);
        if (!tmp.exists()) {
            logger.severe("data file: " + filename + " not found!");
        }

        if (isExtension(filename, "mtz")) {
            diffractionfilter = new MTZFilter();
        } else if (isExtension(filename, new String[]{"cif", "ent"})) {
            diffractionfilter = new CIFFilter();
        } else if (isExtension(filename, new String[]{"cns", "hkl"})) {
            diffractionfilter = new CNSFilter();
        } else {
            diffractionfilter = null;
        }

        this.filename = filename;
        this.weight = weight;
        this.neutron = neutron;
    }

    public DiffractionFile(MolecularAssembly assembly[]) {
        this(assembly[0], 1.0, false);
    }

    public DiffractionFile(MolecularAssembly assembly[], double weight) {
        this(assembly[0], weight, false);
    }

    public DiffractionFile(MolecularAssembly assembly[], double weight,
            boolean neutron) {
        this(assembly[0], weight, neutron);
    }

    public DiffractionFile(MolecularAssembly assembly) {
        this(assembly, 1.0, false);
    }

    public DiffractionFile(MolecularAssembly assembly, double weight) {
        this(assembly, weight, false);
    }

    public DiffractionFile(MolecularAssembly assembly, double weight,
            boolean neutron) {
        String name = removeExtension(assembly.getFile().getPath());

        File tmp = new File(name + ".mtz");
        if (tmp.exists()) {
            logger.info("data file: " + tmp.getName());
            diffractionfilter = new MTZFilter();
        } else {
            tmp = new File(name + ".cif");
            if (tmp.exists()) {
                logger.info("data file: " + tmp.getName());
                diffractionfilter = new CIFFilter();
            } else {
                tmp = new File(name + ".ent");
                if (tmp.exists()) {
                    logger.info("data file: " + tmp.getName());
                    diffractionfilter = new CIFFilter();
                } else {
                    tmp = new File(name + ".cns");
                    if (tmp.exists()) {
                        logger.info("data file: " + tmp.getName());
                        diffractionfilter = new CNSFilter();
                    } else {
                        tmp = new File(name + ".hkl");
                        if (tmp.exists()) {
                            logger.info("data file: " + tmp.getName());
                            diffractionfilter = new CNSFilter();
                        } else {
                            logger.severe("no input data found!");
                            diffractionfilter = null;
                        }
                    }
                }
            }
        }

        this.filename = tmp.getName();
        this.weight = weight;
        this.neutron = neutron;
    }

    public double getWeight() {
        return weight;
    }

    public boolean isNeutron() {
        return neutron;
    }
}
