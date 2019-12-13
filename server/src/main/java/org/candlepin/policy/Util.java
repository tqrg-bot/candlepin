/**
 * Copyright (c) 2009 - 2019 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.policy;

import org.candlepin.model.ConsumerType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * Utility methods used across the rules.
 */
public class Util {

    private Util() {
        // empty by design
    }

    /**
     * Returns true if the consumer's architecture matches that of a product's, or false otherwise.
     *
     * @param productArch The architecture attribute value on the product.
     * @param consumerArch The architecture fact value on the consumer.
     * @param consumerTypeLabel The consumer's type label.
     * @return true of the architecture matches, false otherwise.
     */
    public static boolean architectureMatches(String productArch, String consumerArch,
        String consumerTypeLabel) {
        // Non-system consumers without an architecture fact can pass this rule
        // regardless what arch the product requires.
        if ((consumerArch == null || consumerArch.isEmpty()) &&
            !ConsumerType.ConsumerTypeEnum.SYSTEM.getLabel().equals(consumerTypeLabel)) {
            return true;
        }

        if (productArch != null) {
            List<String> supportedArches = new ArrayList<>(
                Arrays.asList(productArch.toUpperCase().trim().split("\\s*,\\s*")));

            // If X86 is supported, add all variants to this list:
            if (supportedArches.contains("X86")) {
                supportedArches.add("I386");
                supportedArches.add("I586");
                supportedArches.add("I686");
            }

            if (!supportedArches.contains("ALL") &&
                (consumerArch == null ||
                consumerArch.isEmpty() ||
                !supportedArches.contains(consumerArch.toUpperCase()))) {
                return false;
            }
        }

        return true;
    }
}
