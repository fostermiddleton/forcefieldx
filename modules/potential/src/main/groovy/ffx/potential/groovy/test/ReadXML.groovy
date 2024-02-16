//******************************************************************************
//
// Title:       Force Field X.
// Description: Force Field X - Software for Molecular Biophysics.
// Copyright:   Copyright (c) Michael J. Schnieders 2001-2021.
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
//******************************************************************************
//package ffx.potential.groovy.test

import ffx.potential.MolecularAssembly
import ffx.potential.Utilities
import ffx.potential.bonded.Atom
import ffx.potential.bonded.Bond
import ffx.potential.bonded.Molecule
import ffx.potential.cli.PotentialScript
import ffx.potential.parameters.AngleType
import ffx.potential.parameters.AtomType
import ffx.potential.parameters.BondType
import ffx.potential.parameters.ChargeType
import ffx.potential.parameters.ForceField
import ffx.potential.parameters.TorsionType
import ffx.potential.parameters.VDWType
import ffx.potential.parsers.PDBFilter
import ffx.potential.parsers.XYZFilter
import ffx.utilities.Keyword
import org.apache.commons.configuration2.CompositeConfiguration
import org.apache.commons.io.FilenameUtils

import java.util.logging.Level;
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

import static java.lang.String.format
import static ffx.potential.bonded.Atom.ElementSymbol;
import static org.apache.commons.math3.util.FastMath.abs;
import static org.apache.commons.math3.util.FastMath.PI;
import static java.lang.Double.parseDouble;
import static java.lang.Integer.parseInt;


/**
 * The ReadXML script converts an Open Force Field XML parameter file to Patch format.
 *
 * @author Jacob M. Miller
 * <br>
 * Usage:
 * <br>
 * ffxc test.ReadXML &lt;filename&gt;
 */
@Command(description = " Convert XML file to Patch.", name = "ffxc test.ReadXML")
class ReadXML extends PotentialScript {

    /**
     * The final argument(s) should be two filenames.
     */
    @Parameters(arity = "1", paramLabel = "file",
            description = 'XML file to be converted to Patch.')
    List<String> filenames = null

//    final double ANGperNM = 10.0
//    final double KJperKCal = 4.184

    /**
     * Execute the script.
     */
    @Override
    ReadXML run() {

        // Init the context and bind variables.
        if (!init()) {
            return this
        }

        File inputFile = new File(filenames[0])
        String fileName = FilenameUtils.removeExtension(inputFile.getName())
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance()
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder()
        Document doc = dBuilder.parse(inputFile)

        logger.info(format("Filename: %s%n",fileName))
        // Node "ForceField"
        logger.info(format(" Root Element: %s", doc.getDocumentElement().getNodeName()))

        NodeList nodeList = doc.getChildNodes()
        Node node = nodeList.item(0) // Assumed one system label for now (ForceField)
        NodeList childNodes = node.getChildNodes()
        logger.info(" Child Size:" + childNodes.length)

//        int counter = 0
//        NodeList atomNodes
//        for (Node child : childNodes) {
//            if (child.getNodeName() == "AtomTypes") {
//                atomNodes = child.getChildNodes()
//            } else if (child.getNodeName() == "Residues") {
//                NodeList residueNodes = child.getChildNodes()
//            } else if (child.getNodeName() == "HarmonicBondForce") {
//                NodeList hBondForceNodes = child.getChildNodes()
//            } else if (child.getNodeName() == "HarmonicAngleForce") {
//                NodeList hAngleForceNodes = child.getChildNodes()
//            } else if (child.getNodeName() == "PeriodicTorsionForce") {
//                NodeList pTorsionForceNodes = child.getChildNodes()
//            } else if (child.getNodeName() == "NonbondedForce") {
//                NodeList nbForce = child.getChildNodes()
//            }
//        }
//
//        for (Node child : atomNodes) {
//            if (child.hasAttributes()) {
//                logger.info(format("AtomTypes %d: %s %s",counter,child.getNodeName(),child.attributes.length)) // does not have child nodes
//                counter++
//            }
//        }
        for (Node child : childNodes) {
            if (child.hasChildNodes()) {
                switch (child.getNodeName()) {
                    case "AtomTypes":
                        NodeList types = child.getChildNodes()
                        logger.info(format("AtomTypes nodes: %d",types.length))
//                        int counter = 0
                        for (Node atom : types) {
                            if (atom.getNodeName() == "Type") {
                                logger.info(format("%s %s %s %s",atom.getAttribute("name"),atom.getAttribute("class"),atom.getAttribute("element"),atom.getAttribute("mass")))
                            } else if (atom.hasAttributes()) {
                                logger.info("CHECK")
                            }
                        }
                        break

                    case "Residues":
                        NodeList residues = child.getChildNodes()
                        logger.info(format("Residues nodes: %d",residues.length))

                        for (Node res : residues) {
                            if (res.hasChildNodes()) {
                                logger.info(format("Residue: %s",res.getNodeName()))
                                NodeList resProps = res.getChildNodes()

                                for (Node resProp : resProps) {
                                    if (resProp.getNodeName() == "Atom") {
                                        logger.info(format("    Atom: %s %s",resProp.getAttribute("name"),resProp.getAttribute("type")))
                                    } else if (resProp.getNodeName() == "Bond") {
                                        logger.info(format("    Bond: %s %s",resProp.getAttribute("from"),resProp.getAttribute("to")))
                                    } else if (resProp.getNodeName() == "ExternalBond") {
                                        logger.info(format("    ExternalBond: %s",resProp.getAttribute("from")))
                                    } else if (resProp.hasAttributes()) {
                                        logger.info("CHECK")
                                    }
                                }
                            }
                        }
//                        logger.info(format("Residue0: %s",residues.item(0).getNodeName))
                        //residues have children
                        break

                    case "HarmonicBondForce":
                        NodeList bonds = child.getChildNodes()
                        logger.info(format("HarmonicBondForce nodes: %d",bonds.length))

                        for (Node bond : bonds) {
                            if (bond.getNodeName() == "Bond") {
                                logger.info(format("%s %s %s %s",bond.getAttribute("class1"),bond.getAttribute("class2"),bond.getAttribute("length"),bond.getAttribute("k")))
                            } else if (bond.hasAttributes()) {
                                logger.info("CHECK")
                            }
                        }
                        break

                    case "HarmonicAngleForce":
                        NodeList angles = child.getChildNodes()
                        logger.info(format("HarmonicAngleForce nodes: %d",angles.length))
                        break

                    case "PeriodicTorsionForce":
                        NodeList torsions = child.getChildNodes()
                        logger.info(format("PeriodicTorsionForce nodes: %d",torsions.length))
                        break

                    case "NonbondedForce":
                        NodeList nbForces = child.getChildNodes()
                        logger.info(format("NonbondedForce nodes: %d",nbForces.length))
                        break
                }
            }
        }

        return this
    }
}