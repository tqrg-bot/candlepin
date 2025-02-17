/**
 * Copyright (c) 2009 - 2012 Red Hat, Inc.
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
package org.candlepin.model;

import static org.junit.Assert.assertEquals;

import org.candlepin.auth.Principal;
import org.candlepin.auth.UserPrincipal;
import org.candlepin.auth.permissions.Permission;
import org.candlepin.auth.permissions.UsernameConsumersPermission;
import org.candlepin.policy.js.compliance.ComplianceStatus;
import org.candlepin.test.DatabaseTestFixture;
import org.candlepin.test.TestUtil;
import org.candlepin.util.Util;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

/**
 * OwnerInfoCuratorTest
 */
public class OwnerInfoCuratorTest extends DatabaseTestFixture {
    @Inject private OwnerCurator ownerCurator;
    @Inject private ProductCurator productCurator;
    @Inject private PoolCurator poolCurator;
    @Inject private ConsumerCurator consumerCurator;
    @Inject private ConsumerTypeCurator consumerTypeCurator;
    @Inject private EntitlementCurator entitlementCurator;
    @Inject private OwnerInfoCurator ownerInfoCurator;

    private Owner owner;
    private Pool pool1;

    @Before
    public void setUp() {
        owner = createOwner();
        ownerCurator.create(owner);

        Product product1 = TestUtil.createProduct(owner);
        productCurator.create(product1);

        pool1 = createPoolAndSub(owner, product1, 1L,
            Util.yesterday(), Util.tomorrow());
        poolCurator.create(pool1);

        Product product2 = TestUtil.createProduct(owner);
        productCurator.create(product2);

        ConsumerType consumerType = new ConsumerType("system");
        consumerTypeCurator.create(consumerType);

        consumerType = new ConsumerType("domain");
        consumerTypeCurator.create(consumerType);

        consumerType = new ConsumerType("uebercert");
        consumerTypeCurator.create(consumerType);
    }

    @Test
    public void testOwnerInfoNoConsumers() {
        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedConsumers = new HashMap<String, Integer>() {
            {
                put("system", 0);
                put("domain", 0);
                put("uebercert", 0);
            }
        };
        Map<String, Integer> expectedEntitlementsConsumed = new HashMap<String, Integer>() {
            {
                put("system", 0);
                put("domain", 0);
                put("uebercert", 0);
            }
        };

        assertEquals(expectedConsumers, info.getConsumerCounts());
        assertEquals(expectedEntitlementsConsumed, info.getEntitlementsConsumedByType());
    }

    @Test
    public void testOwnerInfoOneSystemNoEntitlements() {
        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        Consumer consumer = new Consumer("test-consumer", "test-user", owner, type);
        consumerCurator.create(consumer);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedConsumers = new HashMap<String, Integer>() {
            {
                put("system", 1);
                put("domain", 0);
                put("uebercert", 0);
            }
        };
        Map<String, Integer> expectedEntitlementsConsumed = new HashMap<String, Integer>() {
            {
                put("system", 0);
                put("domain", 0);
                put("uebercert", 0);
            }
        };

        assertEquals(expectedConsumers, info.getConsumerCounts());
        assertEquals(expectedEntitlementsConsumed, info.getEntitlementsConsumedByType());
    }

    @Test
    public void testOwnerInfoOneSystemEntitlement() {
        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        Consumer consumer = new Consumer("test-consumer", "test-user", owner, type);
        consumerCurator.create(consumer);

        EntitlementCertificate cert = createEntitlementCertificate("fake", "fake");
        Entitlement entitlement = createEntitlement(owner, consumer, pool1, cert);
        entitlement.setQuantity(1);
        entitlementCurator.create(entitlement);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedConsumers = new HashMap<String, Integer>() {
            {
                put("system", 1);
                put("domain", 0);
                put("uebercert", 0);
            }
        };
        Map<String, Integer> expectedEntitlementsConsumed = new HashMap<String, Integer>() {
            {
                put("system", 1);
                put("domain", 0);
                put("uebercert", 0);
            }
        };

        assertEquals(expectedConsumers, info.getConsumerCounts());
        assertEquals(expectedEntitlementsConsumed, info.getEntitlementsConsumedByType());
    }

    @Test
    public void testOwnerInfoOneSystemEntitlementWithQuantityOfTwo() {
        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        Consumer consumer = new Consumer("test-consumer", "test-user", owner, type);
        consumerCurator.create(consumer);

        EntitlementCertificate cert = createEntitlementCertificate("fake", "fake");
        Entitlement entitlement = createEntitlement(owner, consumer, pool1, cert);
        entitlement.setQuantity(2);
        entitlementCurator.create(entitlement);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedConsumers = new HashMap<String, Integer>() {
            {
                put("system", 1);
                put("domain", 0);
                put("uebercert", 0);
            }
        };
        Map<String, Integer> expectedEntitlementsConsumed = new HashMap<String, Integer>() {
            {
                put("system", 2);
                put("domain", 0);
                put("uebercert", 0);
            }
        };

        assertEquals(expectedConsumers, info.getConsumerCounts());
        assertEquals(expectedEntitlementsConsumed, info.getEntitlementsConsumedByType());
    }

    @Test
    public void testOwnerInfoOneOfEachEntitlement() {
        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        Consumer consumer = new Consumer("test-consumer", "test-user", owner, type);
        consumerCurator.create(consumer);
        EntitlementCertificate cert = createEntitlementCertificate("fake", "fake");
        Entitlement entitlement = createEntitlement(owner, consumer, pool1, cert);
        entitlement.setQuantity(1);
        entitlementCurator.create(entitlement);

        type = consumerTypeCurator.lookupByLabel("domain");
        consumer = new Consumer("test-consumer", "test-user", owner, type);
        consumerCurator.create(consumer);

        cert = createEntitlementCertificate("fake", "fake");
        entitlement = createEntitlement(owner, consumer, pool1, cert);
        entitlement.setQuantity(1);
        entitlementCurator.create(entitlement);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedConsumers = new HashMap<String, Integer>() {
            {
                put("system", 1);
                put("domain", 1);
                put("uebercert", 0);
            }
        };
        Map<String, Integer> expectedEntitlementsConsumed = new HashMap<String, Integer>() {
            {
                put("system", 1);
                put("domain", 1);
                put("uebercert", 0);
            }
        };

        assertEquals(expectedConsumers, info.getConsumerCounts());
        assertEquals(expectedEntitlementsConsumed, info.getEntitlementsConsumedByType());
    }

    @Test
    public void testOwnerPoolEntitlementCountPoolOnly() {
        ConsumerType type = consumerTypeCurator.lookupByLabel("domain");
        pool1.setAttribute("requires_consumer_type", type.getLabel());
        owner.addEntitlementPool(pool1);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedPoolCount = new HashMap<String, Integer>() {
            {
                put("system", 0);
                put("domain", 1);
                put("uebercert", 0);
            }
        };

        assertEquals(expectedPoolCount, info.getConsumerTypeCountByPool());

    }

    @Test
    public void testOwnerPoolEntitlementCountProductOnly() {
        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        pool1.getProduct().setAttribute("requires_consumer_type", type.getLabel());
        owner.addEntitlementPool(pool1);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedPoolCount = new HashMap<String, Integer>() {
            {
                put("system", 1);
                put("domain", 0);
                put("uebercert", 0);
            }
        };

        assertEquals(expectedPoolCount, info.getConsumerTypeCountByPool());
    }

    @Test
    public void testOwnerPoolEntitlementPoolOverridesProduct() {
        ConsumerType type = consumerTypeCurator.lookupByLabel("domain");
        ConsumerType type2 = consumerTypeCurator.lookupByLabel("system");
        pool1.setAttribute("requires_consumer_type", type.getLabel());
        pool1.getProduct().setAttribute("requires_consumer_type", type2.getLabel());
        owner.addEntitlementPool(pool1);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedPoolCount = new HashMap<String, Integer>() {
            {
                put("system", 0);
                put("domain", 1);
                put("uebercert", 0);
            }
        };

        assertEquals(expectedPoolCount, info.getConsumerTypeCountByPool());
    }

    @Test
    public void testConsumerTypeCountByPoolExcludesFuturePools() {
        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        pool1.setAttribute("requires_consumer_type", type.getLabel());
        pool1.setStartDate(Util.tomorrow());
        owner.addEntitlementPool(pool1);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedPoolCount = new HashMap<String, Integer>() {
            {
                put("system", 0);
                put("domain", 0);
                put("uebercert", 0);
            }
        };

        assertEquals(expectedPoolCount, info.getConsumerTypeCountByPool());
    }

    @Test
    public void testConsumerTypeCountByPoolExcludesExpiredPools() {
        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        pool1.setAttribute("requires_consumer_type", type.getLabel());
        pool1.setEndDate(Util.yesterday());
        owner.addEntitlementPool(pool1);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedPoolCount = new HashMap<String, Integer>() {
            {
                put("system", 0);
                put("domain", 0);
                put("uebercert", 0);
            }
        };

        assertEquals(expectedPoolCount, info.getConsumerTypeCountByPool());

    }

    @Test
    public void testConsumerTypeCountByPoolPutsDefaultsIntoSystem() {
        owner.addEntitlementPool(pool1);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedPoolCount = new HashMap<String, Integer>() {
            {
                put("system", 1);
                put("domain", 0);
                put("uebercert", 0);
            }
        };

        assertEquals(expectedPoolCount, info.getConsumerTypeCountByPool());
    }

    @Test
    public void testOwnerPoolEnabledCountPoolOnly() {
        ConsumerType type = consumerTypeCurator.lookupByLabel("domain");
        pool1.setAttribute("enabled_consumer_types", type.getLabel());
        owner.addEntitlementPool(pool1);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedPoolCount = new HashMap<String, Integer>() {
            {
                put("domain", 1);
            }
        };

        assertEquals(expectedPoolCount, info.getEnabledConsumerTypeCountByPool());
    }

    @Test
    public void testOwnerPoolEnabledCountProductOnly() {
        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        pool1.getProduct().setAttribute("enabled_consumer_types", type.getLabel());
        owner.addEntitlementPool(pool1);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedPoolCount = new HashMap<String, Integer>() {
            {
                put("system", 1);
            }
        };

        assertEquals(expectedPoolCount, info.getEnabledConsumerTypeCountByPool());
    }

    @Test
    public void testOwnerPoolEnabledPoolOverridesProduct() {
        ConsumerType type = consumerTypeCurator.lookupByLabel("domain");
        ConsumerType type2 = consumerTypeCurator.lookupByLabel("system");
        pool1.setAttribute("enabled_consumer_types", type.getLabel());
        pool1.getProduct().setAttribute("enabled_consumer_types", type2.getLabel());
        owner.addEntitlementPool(pool1);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedPoolCount = new HashMap<String, Integer>() {
            {
                put("domain", 1);
            }
        };

        assertEquals(expectedPoolCount, info.getEnabledConsumerTypeCountByPool());
    }

    @Test
    public void testEnabledConsumerTypeCountByPoolExcludesFuturePools() {
        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        pool1.setAttribute("enabled_consumer_types", type.getLabel());
        pool1.setStartDate(Util.tomorrow());
        owner.addEntitlementPool(pool1);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedPoolCount = new HashMap<String, Integer>();

        assertEquals(expectedPoolCount, info.getEnabledConsumerTypeCountByPool());
    }

    @Test
    public void testEnabledConsumerTypeCountByPoolExcludesExpiredPools() {
        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        pool1.setAttribute("enabled_consumer_types", type.getLabel());
        pool1.setEndDate(Util.yesterday());
        owner.addEntitlementPool(pool1);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedPoolCount = new HashMap<String, Integer>();

        assertEquals(expectedPoolCount, info.getEnabledConsumerTypeCountByPool());
    }

    @Test
    public void testOwnerPoolMultiEnabledCount() {
        ConsumerType type1 = consumerTypeCurator.lookupByLabel("domain");
        ConsumerType type2 = consumerTypeCurator.lookupByLabel("system");
        pool1.setAttribute("enabled_consumer_types", type1.getLabel() +
                           "," + type2.getLabel());
        owner.addEntitlementPool(pool1);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedPoolCount = new HashMap<String, Integer>() {
            {
                put("domain", 1);
                put("system", 1);
            }
        };

        assertEquals(expectedPoolCount, info.getEnabledConsumerTypeCountByPool());
    }

    @Test
    public void testOwnerPoolEnabledZeroCount() {
        pool1.setAttribute("enabled_consumer_types", "non-type");
        owner.addEntitlementPool(pool1);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedPoolCount = new HashMap<String, Integer>();

        assertEquals(expectedPoolCount, info.getEnabledConsumerTypeCountByPool());
    }

    @Test
    public void testOwnerInfoEntitlementsConsumedByFamilyPutsFamilylessInNone() {
        owner.addEntitlementPool(pool1);

        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        Consumer consumer = new Consumer("test-consumer", "test-user", owner, type);
        consumerCurator.create(consumer);

        EntitlementCertificate cert = createEntitlementCertificate("fake", "fake");
        Entitlement entitlement = createEntitlement(owner, consumer, pool1, cert);
        entitlement.setQuantity(1);
        entitlementCurator.create(entitlement);
        pool1.getEntitlements().add(entitlement);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, OwnerInfo.ConsumptionTypeCounts> expected =
            new HashMap<String, OwnerInfo.ConsumptionTypeCounts>() {
                {
                    put("none", new OwnerInfo.ConsumptionTypeCounts(1, 0));
                }
            };

        assertEquals(expected, info.getEntitlementsConsumedByFamily());
    }

    @Test
    public void testOwnerInfoEntitlementsConsumedByFamilyPoolOverridesProduct() {
        owner.addEntitlementPool(pool1);

        pool1.setAttribute("product_family", "test family");
        pool1.getProduct().setAttribute("product_family", "bad test family");

        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        Consumer consumer = new Consumer("test-consumer", "test-user", owner, type);
        consumerCurator.create(consumer);

        EntitlementCertificate cert = createEntitlementCertificate("fake", "fake");
        Entitlement entitlement = createEntitlement(owner, consumer, pool1, cert);
        entitlement.setQuantity(1);
        entitlementCurator.create(entitlement);
        pool1.getEntitlements().add(entitlement);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, OwnerInfo.ConsumptionTypeCounts> expected =
            new HashMap<String, OwnerInfo.ConsumptionTypeCounts>() {
                {
                    put("test family", new OwnerInfo.ConsumptionTypeCounts(1, 0));
                }
            };

        assertEquals(expected, info.getEntitlementsConsumedByFamily());
    }

    @Test
    public void testOwnerInfoEntitlementsConsumedByFamilySortsByFamily() {
        owner.addEntitlementPool(pool1);

        pool1.getProduct().setAttribute("product_family", "test family");

        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        Consumer consumer = new Consumer("test-consumer", "test-user", owner, type);
        consumerCurator.create(consumer);

        EntitlementCertificate cert = createEntitlementCertificate("fake", "fake");
        Entitlement entitlement = createEntitlement(owner, consumer, pool1, cert);
        entitlement.setQuantity(1);
        entitlementCurator.create(entitlement);
        pool1.getEntitlements().add(entitlement);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, OwnerInfo.ConsumptionTypeCounts> expected =
            new HashMap<String, OwnerInfo.ConsumptionTypeCounts>() {
                {
                    put("test family", new OwnerInfo.ConsumptionTypeCounts(1, 0));
                }
            };

        assertEquals(expected, info.getEntitlementsConsumedByFamily());
    }

    @Test
    public void testOwnerInfoEntitlementsConsumedByFamilySeperatesVirtAndPhysical() {
        // other tests look at physical, so just do virtual
        owner.addEntitlementPool(pool1);

        pool1.getProduct().setAttribute("virt_only", "true");

        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        Consumer consumer = new Consumer("test-consumer", "test-user", owner, type);
        consumerCurator.create(consumer);

        EntitlementCertificate cert = createEntitlementCertificate("fake", "fake");
        Entitlement entitlement = createEntitlement(owner, consumer, pool1, cert);
        entitlement.setQuantity(1);
        entitlementCurator.create(entitlement);
        pool1.getEntitlements().add(entitlement);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, OwnerInfo.ConsumptionTypeCounts> expected =
            new HashMap<String, OwnerInfo.ConsumptionTypeCounts>() {
                {
                    put("none", new OwnerInfo.ConsumptionTypeCounts(0, 1));
                }
            };

        assertEquals(expected, info.getEntitlementsConsumedByFamily());

    }

    @Test
    public void testOwnerInfoEntitlementsConsumedByFamilySeperatesVirtExplicitFamily() {
        owner.addEntitlementPool(pool1);

        pool1.getProduct().setAttribute("product_family", "test family");
        pool1.getProduct().setAttribute("virt_only", "true");

        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        Consumer consumer = new Consumer("test-consumer", "test-user", owner, type);
        consumerCurator.create(consumer);

        EntitlementCertificate cert = createEntitlementCertificate("fake", "fake");
        Entitlement entitlement = createEntitlement(owner, consumer, pool1, cert);
        entitlement.setQuantity(1);
        entitlementCurator.create(entitlement);
        pool1.getEntitlements().add(entitlement);



        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, OwnerInfo.ConsumptionTypeCounts> expected =
            new HashMap<String, OwnerInfo.ConsumptionTypeCounts>() {
                {
                    put("test family", new OwnerInfo.ConsumptionTypeCounts(0, 1));
                }
            };

        assertEquals(expected, info.getEntitlementsConsumedByFamily());
    }

    @Test
    public void testConsumerGuestCount() {
        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        Consumer guest1 = new Consumer("test-consumer", "test-user", owner, type);
        guest1.setFact("virt.is_guest", "true");
        consumerCurator.create(guest1);

        Consumer guest2 = new Consumer("test-consumer", "test-user", owner, type);
        guest2.setFact("virt.is_guest", "true");
        consumerCurator.create(guest2);

        Consumer physical1 = new Consumer("test-consumer", "test-user", owner, type);
        physical1.setFact("virt.is_guest", "false");
        consumerCurator.create(physical1);

        // Second physical machine with no is_guest fact set.
        Consumer physical2 = new Consumer("test-consumer2", "test-user", owner, type);
        consumerCurator.create(physical2);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);
        assertEquals((Integer) 2, info.getConsumerGuestCounts().get(OwnerInfo.GUEST));
        assertEquals((Integer) 2, info.getConsumerGuestCounts().get(OwnerInfo.PHYSICAL));


        // Create another owner to make sure we don't see another owners consumers:
        Owner anotherOwner = createOwner();
        ownerCurator.create(anotherOwner);
        info = ownerInfoCurator.lookupByOwner(anotherOwner);
        assertEquals((Integer) 0, info.getConsumerGuestCounts().get(OwnerInfo.GUEST));
        assertEquals((Integer) 0, info.getConsumerGuestCounts().get(OwnerInfo.PHYSICAL));
    }

    @Test
    public void testConsumerCountsByEntitlementStatus() {
        setupConsumerCountTest("test-user");

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);
        assertConsumerCountsByEntitlementStatus(info);
    }

    @Test
    public void testConsumerCountsByEntitlementStatusExcludesUebercertConsumers() {
        setupConsumerCountTest("test-user");

        ConsumerType ueberType = consumerTypeCurator.lookupByLabel("uebercert");
        Consumer ueberConsumer = new Consumer("test-ueber", "test-user", owner,
            ueberType);
        ueberConsumer.setEntitlementStatus(ComplianceStatus.GREEN);
        consumerCurator.create(ueberConsumer);

        // Even though we've added an ubercert consumer, the counts should remain
        // as expected.
        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);
        assertConsumerCountsByEntitlementStatus(info);
    }

    @Test
    public void testPermissionsAppliedWhenDeterminingConsumerCountsByEntStatus() {
        User mySystemsUser = setupOnlyMyConsumersPrincipal();
        setupConsumerCountTest("test-user");
        setupConsumerCountTest(mySystemsUser.getUsername());

        // Should only get the counts for a single setup case above.
        // The test-user consumers should be ignored.
        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);
        assertConsumerCountsByEntitlementStatus(info);
    }

    @Test
    public void testPermissionsAppliedForConsumerCounts() {
        User mySystemsUser = setupOnlyMyConsumersPrincipal();

        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        Consumer consumer = new Consumer("my-system-1", mySystemsUser.getUsername(),
            owner, type);
        consumerCurator.create(consumer);

        consumer = new Consumer("not-my-system", "another-user", owner, type);
        consumerCurator.create(consumer);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedConsumers = new HashMap<String, Integer>() {
            {
                put("system", 1);
                put("domain", 0);
                put("uebercert", 0);
            }
        };

        assertEquals(expectedConsumers, info.getConsumerCounts());
    }

    @Test
    public void testPermissionsAppliedForConsumerTypeEntitlementEntitlementsConsumedByType() {
        User mySystemsUser = setupOnlyMyConsumersPrincipal();

        ConsumerType type = consumerTypeCurator.lookupByLabel("system");
        Consumer consumer = new Consumer("my-system-1", mySystemsUser.getUsername(),
            owner, type);
        consumerCurator.create(consumer);

        EntitlementCertificate myConsumersCert = createEntitlementCertificate("mine",
            "mine");
        Entitlement myConsumerEntitlement = createEntitlement(owner, consumer, pool1,
            myConsumersCert);
        myConsumerEntitlement.setQuantity(2);
        entitlementCurator.create(myConsumerEntitlement);
        consumerCurator.merge(consumer);

        consumer = new Consumer("not-my-system", "another-user", owner, type);
        consumerCurator.create(consumer);

        EntitlementCertificate otherCert = createEntitlementCertificate("other", "other");
        Entitlement otherEntitlement = createEntitlement(owner, consumer, pool1, otherCert);
        otherEntitlement.setQuantity(1);
        entitlementCurator.create(otherEntitlement);
        consumerCurator.merge(consumer);

        OwnerInfo info = ownerInfoCurator.lookupByOwner(owner);

        Map<String, Integer> expectedConsumers = new HashMap<String, Integer>() {
            {
                put("system", 1);
                put("domain", 0);
                put("uebercert", 0);
            }
        };

        Map<String, Integer> expectedEntitlementsConsumed = new HashMap<String, Integer>() {
            {
                put("system", 2);
                put("domain", 0);
                put("uebercert", 0);
            }
        };

        assertEquals(expectedConsumers, info.getConsumerCounts());
        assertEquals(expectedEntitlementsConsumed, info.getEntitlementsConsumedByType());
    }

    private void setupConsumerCountTest(String username) {
        ConsumerType systemType = consumerTypeCurator.lookupByLabel("system");
        Consumer consumer1 = new Consumer("test-consumer1", username, owner, systemType);
        consumer1.setEntitlementStatus(ComplianceStatus.GREEN);
        consumerCurator.create(consumer1);

        Consumer consumer2 = new Consumer("test-consumer2", username, owner, systemType);
        consumer2.setEntitlementStatus(ComplianceStatus.RED);
        consumerCurator.create(consumer2);

        Consumer consumer3 = new Consumer("test-consumer3", username, owner, systemType);
        consumer3.setEntitlementStatus(ComplianceStatus.GREEN);
        consumerCurator.create(consumer3);

        Consumer consumer4 = new Consumer("test-consumer3", username, owner, systemType);
        consumer4.setEntitlementStatus(ComplianceStatus.YELLOW);
        consumerCurator.create(consumer4);

    }

    private void assertConsumerCountsByEntitlementStatus(OwnerInfo info) {
        assertEquals((Integer) 2, info.getConsumerCountByStatus(ComplianceStatus.GREEN));
        assertEquals((Integer) 1, info.getConsumerCountByStatus(ComplianceStatus.RED));
        assertEquals((Integer) 1, info.getConsumerCountByStatus(ComplianceStatus.YELLOW));

        // Make sure unknown statuses report 0 consumers:
        assertEquals((Integer) 0, info.getConsumerCountByStatus("Unknown Status"));
    }

    private User setupOnlyMyConsumersPrincipal() {
        Set<Permission> perms = new HashSet<Permission>();
        User u = new User("MySystemsAdmin", "passwd");
        perms.add(new UsernameConsumersPermission(u, owner));
        Principal p = new UserPrincipal(u.getUsername(), perms, false);
        setupPrincipal(p);
        return u;
    }

}
