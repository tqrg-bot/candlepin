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

import static org.junit.jupiter.api.Assertions.*;

import org.candlepin.model.ConsumerType;

import org.junit.jupiter.api.Test;


/**
 * Test suite for Util class
 */
public class UtilTest {

    @Test
    public void testArchitectureMatches() {
        boolean matches = Util.architectureMatches("X86", "x86",
            ConsumerType.ConsumerTypeEnum.SYSTEM.getLabel());
        assertTrue(matches);
    }

    @Test
    public void testArchitectureMatchesX86variant() {
        boolean matches = Util.architectureMatches("X86", "i386",
            ConsumerType.ConsumerTypeEnum.SYSTEM.getLabel());
        assertTrue(matches);
    }

    @Test
    public void testArchitectureMatchesWithMultipleArchesAndSpaces() {
        boolean matches = Util.architectureMatches(" X86 , aarch64, whatever", "x86",
            ConsumerType.ConsumerTypeEnum.SYSTEM.getLabel());
        assertTrue(matches);
    }

    @Test
    public void testArchitectureMatchesWithALL() {
        boolean matches = Util.architectureMatches("ALL", "x86",
            ConsumerType.ConsumerTypeEnum.SYSTEM.getLabel());
        assertTrue(matches);
    }

    @Test
    public void testArchitectureMatchesWhenProductArchIsNull() {
        boolean matches = Util.architectureMatches(null, "x86",
            ConsumerType.ConsumerTypeEnum.SYSTEM.getLabel());
        assertTrue(matches);
    }

    @Test
    public void testArchitectureDoesNotMatch() {
        boolean matches = Util.architectureMatches("X86", "aarch64",
            ConsumerType.ConsumerTypeEnum.SYSTEM.getLabel());
        assertFalse(matches);
    }

    @Test
    public void testArchitectureDoesNotMatchWhenConsumerArchIsNull() {
        boolean matches = Util.architectureMatches("X86", null,
            ConsumerType.ConsumerTypeEnum.SYSTEM.getLabel());
        assertFalse(matches);
    }

    @Test
    public void testArchitectureMatchesWhenConsumerArchIsNullButIsNonSystemConsumer() {
        boolean matches = Util.architectureMatches("X86", null,
            ConsumerType.ConsumerTypeEnum.HYPERVISOR.getLabel());
        assertTrue(matches);
    }
}
