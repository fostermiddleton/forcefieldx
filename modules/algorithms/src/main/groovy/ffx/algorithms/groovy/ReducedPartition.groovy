package ffx.algorithms.groovy

import ffx.algorithms.cli.AlgorithmsScript
import ffx.algorithms.cli.ManyBodyOptions
import ffx.algorithms.optimize.RotamerOptimization
import ffx.algorithms.optimize.TitrationManyBody
import ffx.numerics.math.DoubleMath
import ffx.potential.ForceFieldEnergy
import ffx.potential.MolecularAssembly
import ffx.potential.bonded.Atom
import ffx.potential.bonded.LambdaInterface
import ffx.potential.bonded.Residue
import ffx.potential.bonded.Rotamer
import ffx.potential.bonded.RotamerLibrary
import ffx.potential.cli.AlchemicalOptions
import ffx.potential.parsers.PDBFilter
import org.apache.commons.configuration2.CompositeConfiguration
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Parameters

import static ffx.potential.bonded.NamingUtils.renameAtomsToPDBStandard
import static java.lang.String.format

/**
 * The ReductionPartition script performs a discrete optimization using a many-body expansion and elimination expressions.
 * <br>
 * Usage:
 * <br>
 * ffxc ManyBody [options] &lt;filename&gt;
 */
@Command(description = " Run ReducedPartition function for free energy change.", name = "ffxc ReducedPartition")
class ReducedPartition extends AlgorithmsScript {

    @Mixin
    ManyBodyOptions manyBodyOptions

    @Mixin
    AlchemicalOptions alchemicalOptions

    @CommandLine.Option(names = ["--resC", "--residueChain"], paramLabel = "A",
            description = "The chain that is mutating.")
    private String mutatingChain = 'A'

    @CommandLine.Option(names = ["-n", "--residueName"], paramLabel = "ALA",
            description = "Mutant residue.")
    private String resName

    @CommandLine.Option(names = ["--rEE", "--ro-ensembleEnergy"], paramLabel = "0.0",
            description = "Keep permutations within ensemble Energy kcal/mol from the GMEC.")
    private String ensembleEnergy = "0.0"

    @CommandLine.Option(names = ["--un", "--unfolded"], paramLabel = "false",
            description = "Run the unfolded state tripeptide.")
    private boolean unfolded = false

    @CommandLine.Option(names = ["--pKa"], paramLabel = "false",
            description = "Calculating free energy change for pKa shift.")
    private boolean pKa = false

    @CommandLine.Option(names = ["--pB", "--printBoltzmann"], paramLabel = "false",
            description = "Save the Boltzmann weights of protonated residue and total Boltzmann weights.")
    private boolean printBoltzmann = false

    @CommandLine.Option(names = ["--pF", "--printFiles"], paramLabel = "false",
            description = "Write to an energy restart file and ensemble file.")
    private boolean printFiles = false

    @CommandLine.Option(names = ["--rCS", "--recomputeSelf"], paramLabel = "false",
            description = "Recompute the self energies after loading a restart file.")
    private boolean recomputeSelf = false

    /**
     * An XYZ or PDB input file.
     */
    @Parameters(arity = "1", paramLabel = "file",
            description = "XYZ or PDB input file.")
    private List<String> filenames = null

    ForceFieldEnergy potentialEnergy
    TitrationManyBody titrationManyBody
    MolecularAssembly mutatedAssembly
    Binding mutatorBinding
    List<Residue> residues
    List<Residue> selectedResidues

    private String unfoldedFileName

    /**
     * ManyBody Constructor.
     */
    ReducedPartition() {
        this(new Binding())
    }

    /**
     * ManyBody Constructor.
     * @param binding The Groovy Binding to use.
     */
    ReducedPartition(Binding binding) {
        super(binding)
    }

    /**
     * {@inheritDoc}
     */
    @Override
    ReducedPartition run() {
        if (!init()) {
            return this
        }

        double titrationPH = manyBodyOptions.getTitrationPH()
        double inclusionCutoff = manyBodyOptions.getInclusionCutoff()
        int mutatingResidue = manyBodyOptions.getInterestedResidue()
        boolean onlyProtons = manyBodyOptions.getOnlyProtons()
        boolean onlyTitration = manyBodyOptions.getOnlyTitration()
        if (manyBodyOptions.getTitration()) {
            System.setProperty("manybody-titration", "true")
        }

        boolean lambdaTerm = alchemicalOptions.hasSoftcore()
        if (lambdaTerm) {
            // Turn on softcore van der Waals
            System.setProperty("lambdaterm", "true")
            // Turn of alchemical electrostatics
            System.setProperty("elec-lambdaterm", "false")
            // Turn on intra-molecular softcore
            System.setProperty("intramolecular-softcore", "true");
        }
        System.setProperty("ro-ensembleEnergy", ensembleEnergy)
        activeAssembly = getActiveAssembly(filenames.get(0))

        //Make an unfolded state assembly when predicting folding free energy difference
        if (unfolded) {
            unfoldedFileName = "wt" + mutatingResidue + ".pdb"
            List<Atom> atoms = activeAssembly.getAtomList()
            Set<Atom> excludeAtoms = new HashSet<>()
            for (Atom atom : atoms) {
                if (atom.getResidueNumber() < mutatingResidue - 1 || atom.getResidueNumber() > mutatingResidue + 1) {
                    excludeAtoms.add(atom)
                } else if (atom.getResidueNumber() == mutatingResidue - 1 && atom.getName() == "H") {
                    excludeAtoms.add(atom)
                }
            }
            File file = new File(unfoldedFileName)
            PDBFilter pdbFilter = new PDBFilter(file, activeAssembly, activeAssembly.getForceField(),
                    activeAssembly.getProperties())
            pdbFilter.writeFile(file, false, excludeAtoms, true, true)
            setActiveAssembly(getActiveAssembly(unfoldedFileName))
        }

        String[] titratableResidues = ["HIS", "HIE", "HID", "GLU", "GLH", "ASP", "ASH", "LYS", "LYD"]
        List<String> titratableResiudesList = Arrays.asList(titratableResidues);
        double[] boltzmannWeights = new double[2]
        double[] offsets = new double[2]
        double[][] titrateArray
        double[] titrateBoltzmann
        double totalBoltzmann = 0
        List<Residue> residueList = activeAssembly.getResidueList()

        List<Integer> residueNumber = new ArrayList<>()
        for (Residue residue : residueList) {
            residueNumber.add(residue.getResidueNumber())
        }


        String mutatedFileName = ""
        //Call the MutatePDB script and mutate the residue of interest
        if (filenames.size() == 1 && mutatingResidue != -1) {
            if (unfolded) {
                mutatorBinding = new Binding('-r', mutatingResidue.toString(), '-n', resName, unfoldedFileName)
            } else {
                mutatorBinding = new Binding('-r', mutatingResidue.toString(), '-n', resName, filenames.get(0), '--ch', mutatingChain)
            }

            MutatePDB mutatePDB = new MutatePDB(mutatorBinding)
            mutatePDB.run()
            mutatedFileName = mutatorBinding.getProperty('versionFileName')
        }

        String listResidues = ""
        //Select residues with alpha carbons within the inclusion cutoff
        if (mutatingResidue != -1 && inclusionCutoff != -1) {
            listResidues = manyBodyOptions.selectInlcusionResidues(residueList, mutatingResidue, onlyTitration, onlyProtons, inclusionCutoff)
        }

        //Select only the titrating residues or the titrating residues and those within the inclusion cutoff
        if (onlyTitration || onlyProtons) {
            listResidues = manyBodyOptions.selectInclusionResidues(residueList, mutatingResidue, onlyTitration, onlyProtons, inclusionCutoff)
        }

        String filename = filenames.get(0)

        //Set the number of assemblies the partition function will be calculated for
        int numLoop = 1
        if (mutatingResidue != -1) {
            numLoop = 2
        }

        List<Residue> titrateResidues = new ArrayList<>()

        //Calculate all possible permutations for the number of assembles
        for (int j = 0; j < numLoop; j++) {

            // Load the MolecularAssembly second molecular assembly if applicable.
            if (j > 0) {
                if (filenames.size() == 1) {
                    mutatedAssembly = getActiveAssembly(mutatedFileName)
                    setActiveAssembly(mutatedAssembly)
                    logger.info(activeAssembly.getResidueList().toString())
                    activeAssembly.getPotentialEnergy().energy()
                    filename = mutatedFileName
                } else {
                    setActiveAssembly(getActiveAssembly(filenames.get(j)))
                    filename = filenames.get(j)
                }
            }

            if (activeAssembly == null) {
                logger.info(helpString())
                return this
            }

            CompositeConfiguration properties = activeAssembly.getProperties()

            // Application of rotamers uses side-chain atom naming from the PDB.
            if (properties.getBoolean("standardizeAtomNames", false)) {
                renameAtomsToPDBStandard(activeAssembly)
            }

            activeAssembly.getPotentialEnergy().setPrintOnFailure(false, false)
            potentialEnergy = activeAssembly.getPotentialEnergy()

            if (!pKa || onlyTitration || onlyProtons) {
                manyBodyOptions.setListResidues(listResidues)
            }


            // Collect residues to optimize.
            residues = manyBodyOptions.collectResidues(activeAssembly)
            if (residues == null || residues.isEmpty()) {
                logger.info(" There are no residues in the active system to optimize.")
                return this
            }

            // Handle rotamer optimization with titration.
            if (manyBodyOptions.getTitration()) {
                logger.info("\n Adding titration hydrogen to : " + filenames.get(0) + "\n")

                // Collect residue numbers.
                List<Integer> resNumberList = new ArrayList<>()
                for (Residue residue : residues) {
                    resNumberList.add(residue.getResidueNumber())
                    if (pKa) {
                        if (titratableResiudesList.contains(residue.getName())) {
                            titrateResidues.add(residue)
                        }
                    }
                }

                // Create new MolecularAssembly with additional protons and update the ForceFieldEnergy
                titrationManyBody = new TitrationManyBody(filename, activeAssembly.getForceField(),
                        resNumberList, titrationPH)
                MolecularAssembly protonatedAssembly = titrationManyBody.getProtonatedAssembly()
                setActiveAssembly(protonatedAssembly)
                potentialEnergy = protonatedAssembly.getPotentialEnergy()
            }
            
            if (lambdaTerm) {
                alchemicalOptions.setFirstSystemAlchemistry(activeAssembly)
                LambdaInterface lambdaInterface = (LambdaInterface) potentialEnergy
                double lambda = alchemicalOptions.getInitialLambda()
                logger.info(format(" Setting ManyBody softcore lambda to: %5.3f", lambda))
                lambdaInterface.setLambda(lambda)
            }

            //Run rotamer optimization with specified parameter
            RotamerOptimization rotamerOptimization = new RotamerOptimization(activeAssembly,
                    potentialEnergy, algorithmListener)
            rotamerOptimization.setPrintFiles(printFiles)
            rotamerOptimization.setWriteEnergyRestart(printFiles)
            rotamerOptimization.setOnlyProtons(onlyProtons)
            rotamerOptimization.setRecomputeSelf(recomputeSelf)
            rotamerOptimization.setpH(titrationPH)

            manyBodyOptions.initRotamerOptimization(rotamerOptimization, activeAssembly)

            selectedResidues = rotamerOptimization.getResidues()

            logger.info("\n Initial Potential Energy:")
            potentialEnergy.energy(false, true)

            logger.info("\n Initial Rotamer Torsion Angles:")
            RotamerLibrary.measureRotamers(selectedResidues, false)

            // Run the optimization.
            rotamerOptimization.optimize(manyBodyOptions.getAlgorithm(selectedResidues.size()))

            int[] currentRotamers = new int[selectedResidues.size()]

            //Keep track of the number of titrating residues
            if (pKa) {
                titrateArray = new double[selectedResidues.size()][54]
            }

            //Calculate possible permutations for assembly
            rotamerOptimization.checkPermutations(selectedResidues.toArray() as Residue[], 0, currentRotamers, titrateArray,
                    manyBodyOptions.getAlgorithm(selectedResidues.size()))

            //Collect the Bolztmann weights and calculated offset of each assembly
            boltzmannWeights[j] = rotamerOptimization.getTotalBoltzmann()
            offsets[j] = rotamerOptimization.getRefEnergy()

            //Calculate the fraction protonated for the titratable residues
            if (pKa) {
                titrateArray = rotamerOptimization.getFraction()
                if (printBoltzmann) {
                    titrateBoltzmann = rotamerOptimization.getTitrateBoltzmann()
                    totalBoltzmann = rotamerOptimization.getTotalBoltzmann()
                }

            }
        }

        //Print information from the fraction protonated calculations
        if (pKa) {
            FileWriter fileWriter = new FileWriter("populations.txt")
            int titrateCount = 0
            for (Residue residue : selectedResidues) {
                double sum1 = 0
                double sum2 = 0
                double sum3 = 0
                Rotamer[] rotamers = residue.getRotamers()
                for(Rotamer rotamer: rotamers){
                    String rotPop = String.format("%.6f",titrateArray[titrateCount][rotamer.getWeight()])
                    fileWriter.write(residue.getName() + residue.getResidueNumber() +"\t" +
                            rotamer.toString() + "\t" + rotPop + "\n")
                    switch(rotamer.getName()) {
                        case "HIS":
                        case "LYS":
                        case "GLU":
                        case "ASP":
                            sum1 += titrateArray[titrateCount][rotamer.getWeight()]
                            break
                        case "HIE":
                        case "LYD":
                        case "GLH":
                        case "ASH":
                            sum2 += titrateArray[titrateCount][rotamer.getWeight()]
                            break
                        case "HID":
                            sum3 += titrateArray[titrateCount][rotamer.getWeight()]
                            break
                        default:
                            break
                    }
                }
                String sumOne = String.format("%.6f",sum1)
                String sumTwo = String.format("%.6f",sum2)
                String sumThree = String.format("%.6f",sum3)
                switch(residue.getName()) {
                    case "HIS":
                        logger.info(residue.getResidueNumber() +"\tHIS" +  "\t" + sumOne + "\t" +
                                "HIE" + "\t" + sumTwo + "\t" +
                                "HID" + "\t" + sumThree)
                        break
                    case "LYS":
                        logger.info(residue.getResidueNumber() +"\tLYS" +  "\t" + sumOne + "\t" +
                                "LYD" +  "\t" + sumTwo)
                        break
                    case "ASH":
                        logger.info(residue.getResidueNumber() +"\tASP" +  "\t" + sumOne + "\t" +
                                "ASH" + "\t" + sumTwo)
                        break
                    case "GLH":
                        logger.info(residue.getResidueNumber() +"\tGLU" +  "\t" + sumOne + "\t" +
                                "GLH" + "\t" + sumTwo)
                        break
                    default:
                        break
                }

                if (printBoltzmann) {
                    logger.info("Residue " + residue.getName() + residue.getResidueNumber() + " Protonated Boltzmann: " +
                            titrateBoltzmann[titrateCount])
                    logger.info("Total Boltzmann: " + totalBoltzmann)
                }


                titrateCount += 1
            }
            fileWriter.close()
            System.out.println("Successfully wrote to the populations file.")
        } else {
            //Calculate Gibbs free energy change of mutating residues
            double gibbs = -(0.6) * (Math.log(boltzmannWeights[1] / boltzmannWeights[0]))
            logger.info("\n Gibbs Free Energy Change: " + gibbs)
        }


        return this
    }

    /**
     * Returns the potential energy of the active assembly. Used during testing assertions.
     * @return potentialEnergy Potential energy of the active assembly.
     */
    ForceFieldEnergy getPotential() {
        return potentialEnergy
    }

    private static boolean evaluateAllRotDist(Residue residueA, Residue residueB, double inclusion) {
        residueA.setRotamers(RotamerLibrary.getDefaultLibrary())
        residueB.setRotamers(RotamerLibrary.getDefaultLibrary())
        Rotamer[] rotamersA = residueA.getRotamers()
        Rotamer[] rotamersB = residueB.getRotamers()
        double[] aCoor = new double[3]
        double[] bCoor = new double[3]
        for (Rotamer rotamerA : rotamersA) {
            residueA.setRotamer(rotamerA)
            for (Rotamer rotamerB : rotamersB) {
                residueB.setRotamer(rotamerB)
                for (Atom atomA : residueA.getAtomList()) {
                    for (Atom atomB : residueB.getAtomList()) {
                        double dist = DoubleMath.dist(atomA.getXYZ(aCoor), atomB.getXYZ(bCoor))
                        if (dist <= inclusion) {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }
}
