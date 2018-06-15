
package xray

import groovy.cli.Option
import groovy.cli.Unparsed
import groovy.cli.picocli.CliBuilder

import ffx.xray.parsers.MTZFilter

/**
 * Use AverageMTZ to provide two MTZ files and an iteration for a cumulative moving average.
 * <br>
 * Usage:
 * <br>
 * ffxc xray.AverageMTZ &lt;filename1&gt; &lt;filename2&gt;
 */
class AverageMTZ extends Script {

    /**
     * Options for the AverageMTZ  Script.
     * <br>
     * Usage:
     * <br>
     * ffxc xray.AverageMTZ &lt;filename1&gt; &lt;filename2&gt;
     */
    public class Options {
        /**
         * -h or --help to print a help message
         */
        @Option(shortName = 'h', defaultValue = 'false', description = 'Print this help message.')
        boolean help;
        /**
         * -i or --iteration the current moving average iteration
         */
        @Option(shortName = 'i', defaultValue = '1',
                description = 'The current moving average iteration (use 1 for a "normal" average of two files).')
        int iteration;
        /**
         * The final arguments should be two MTZ filenames.
         */
        @Unparsed(description = 'Two MTZ filenames.')
        List<String> filenames
    }

    /**
     * Execute the script.
     */
    def run() {
        def cli = new CliBuilder();
        cli.name = "ffxc xray.AverageMTZ";

        def options = new Options()
        cli.parseFromInstance(options, args)

        if (options.help == true) {
            return cli.usage()
        }

        List<String> arguments = options.filenames

        String mtzfile1
        String mtzfile2

        if (arguments != null && arguments.size() > 1) {
            // Read in command line.
            mtzfile1 = arguments.get(0)
            mtzfile2 = arguments.get(1)
        } else {
            return cli.usage()
        }

        File file1 = new File(mtzfile1);
        if (!file1.exists()){
            println("File: " + mtzfile1 + " not found!");
            return;
        }

        File file2 = new File(mtzfile2);
        if (!file2.exists()){
            println("File: " + mtzfile2 + " not found!");
            return;
        }

        MTZFilter mtzfilter = new MTZFilter();
        mtzfilter.averageFcs(file1, file2, mtzfilter.getReflectionList(file1), Integer.parseInt(options.iteration), null);
    }
}

/**
 * Title: Force Field X.
 *
 * Description: Force Field X - Software for Molecular Biophysics.
 *
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2018.
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