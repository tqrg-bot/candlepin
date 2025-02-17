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

import static org.junit.Assert.*;

import org.candlepin.model.ConsumerType.ConsumerTypeEnum;
import org.candlepin.test.DatabaseTestFixture;
import org.candlepin.test.TestUtil;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.persistence.PersistenceException;
import javax.persistence.RollbackException;

/**
 *
 */
public class OwnerCuratorTest extends DatabaseTestFixture {
    @Inject private OwnerCurator ownerCurator;
    @Inject private ProductCurator productCurator;
    @Inject private PoolCurator poolCurator;
    @Inject private ConsumerCurator consumerCurator;
    @Inject private ConsumerTypeCurator consumerTypeCurator;
    @Inject private EntitlementCurator entitlementCurator;

    @Test
    public void basicImport() {
        Owner owner = new Owner("testing");
        owner.setId("testing-primary-key");

        this.ownerCurator.replicate(owner);

        assertEquals("testing",
                this.ownerCurator.find("testing-primary-key").getKey());
    }

    @Test(expected = RollbackException.class)
    public void primaryKeyCollision() {
        Owner owner = new Owner("dude");
        owner = this.ownerCurator.create(owner);

        Owner newOwner = new Owner("someoneElse");
        newOwner.setId(owner.getId());

        this.ownerCurator.replicate(newOwner);
    }

    @Test(expected = PersistenceException.class)
    public void upstreamUuidConstraint() {
        UpstreamConsumer uc = new UpstreamConsumer("sameuuid");

        Owner owner1 = new Owner("owner1");
        owner1.setUpstreamConsumer(uc);
        Owner owner2 = new Owner("owner2");
        owner2.setUpstreamConsumer(uc);

        ownerCurator.create(owner1);
        ownerCurator.create(owner2);
    }

    private void associateProductToOwner(Owner o, Product p, Product provided) {
        Set<Product> providedProducts = new HashSet<Product>();
        providedProducts.add(provided);

        Pool pool = TestUtil.createPool(o, p, providedProducts, 5);
        poolCurator.create(pool);

        Consumer c = createConsumer(o);
        EntitlementCertificate cert = createEntitlementCertificate("key", "certificate");
        Entitlement ent = createEntitlement(o, c, pool, cert);
        entitlementCurator.create(ent);
    }

    @Test
    public void testLookupMultipleOwnersByMultipleActiveProducts() {
        Owner owner = createOwner();
        Owner owner2 = createOwner();

        Product product = TestUtil.createProduct(owner);
        Product provided = TestUtil.createProduct(owner);
        Product product2 = TestUtil.createProduct(owner2);
        Product provided2 = TestUtil.createProduct(owner2);
        productCurator.create(product);
        productCurator.create(provided);
        productCurator.create(product2);
        productCurator.create(provided2);

        associateProductToOwner(owner, product, provided);
        associateProductToOwner(owner2, product2, provided2);

        List<String> productIds = new ArrayList<String>();
        productIds.add(provided.getId());
        productIds.add(provided2.getId());
        List<Owner> results = ownerCurator.lookupOwnersByActiveProduct(productIds);

        assertEquals(2, results.size());
    }

    @Test
    public void testLookupOwnerByActiveProduct() {
        Owner owner = createOwner();

        Product product = TestUtil.createProduct(owner);
        Product provided = TestUtil.createProduct(owner);
        productCurator.create(product);
        productCurator.create(provided);

        associateProductToOwner(owner, product, provided);

        List<String> productIds = new ArrayList<String>();
        productIds.add(provided.getId());
        List<Owner> results = ownerCurator.lookupOwnersByActiveProduct(productIds);

        assertEquals(1, results.size());
        assertEquals(owner, results.get(0));
    }

    @Test
    public void testLookupOwnersByActiveProductWithExpiredEntitlements() {
        Owner owner = createOwner();

        Product product = TestUtil.createProduct(owner);
        Product provided = TestUtil.createProduct(owner);
        productCurator.create(product);
        productCurator.create(provided);

        Set<Product> providedProducts = new HashSet<Product>();
        providedProducts.add(provided);

        // Create pool with end date in the past.
        Pool pool = new Pool(
            owner,
            product,
            providedProducts,
            Long.valueOf(5),
            TestUtil.createDate(2009, 11, 30),
            TestUtil.createDate(2010, 11, 30),
            "SUB234598S",
            "ACC123",
            "ORD222"
        );

        poolCurator.create(pool);

        Consumer consumer = createConsumer(owner);
        consumerCurator.create(consumer);

        EntitlementCertificate cert = createEntitlementCertificate("key", "certificate");
        Entitlement ent = createEntitlement(owner, consumer, pool, cert);
        entitlementCurator.create(ent);

        List<String> productIds = new ArrayList<String>();
        productIds.add(provided.getId());
        List<Owner> results = ownerCurator.lookupOwnersByActiveProduct(productIds);

        assertTrue(results.isEmpty());
    }

    @Test
    public void lookupByUpstreamUuid() {
        Owner owner = new Owner("owner1");
        // setup some data
        owner = ownerCurator.create(owner);
        ConsumerType type = new ConsumerType(ConsumerTypeEnum.CANDLEPIN);
        consumerTypeCurator.create(type);
        UpstreamConsumer uc = new UpstreamConsumer("test-upstream-consumer",
               owner, type, "someuuid");
        owner.setUpstreamConsumer(uc);
        ownerCurator.merge(owner);

        // ok let's see if this works
        Owner found = ownerCurator.lookupWithUpstreamUuid("someuuid");

        // verify all is well in the world
        assertNotNull(found);
        assertEquals(owner.getId(), found.getId());
    }

    @Test
    public void getConsumerUuids() {
        ConsumerType type = new ConsumerType(ConsumerTypeEnum.SYSTEM);
        consumerTypeCurator.create(type);

        Owner owner = new Owner("owner");
        Owner otherOwner = new Owner("other owner");

        ownerCurator.create(owner);
        ownerCurator.create(otherOwner);

        Consumer c1 = new Consumer("name1", "uname1", owner, type);
        Consumer c2 = new Consumer("name2", "uname2", owner, type);
        Consumer c3 = new Consumer("name3", "uname3", otherOwner, type);
        consumerCurator.create(c1);
        consumerCurator.create(c2);
        consumerCurator.create(c3);

        List<String> result = ownerCurator.getConsumerUuids(owner.getKey());
        assertEquals(2, result.size());
        assertTrue(result.contains(c1.getUuid()));
        assertTrue(result.contains(c2.getUuid()));
        assertFalse(result.contains(c3.getUuid()));
    }
}
