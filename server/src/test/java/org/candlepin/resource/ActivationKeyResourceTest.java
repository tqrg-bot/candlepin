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
package org.candlepin.resource;

import static org.junit.Assert.*;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

import org.candlepin.common.exceptions.BadRequestException;
import org.candlepin.controller.PoolManager;
import org.candlepin.model.Owner;
import org.candlepin.model.Pool;
import org.candlepin.model.Product;
import org.candlepin.model.ProductCurator;
import org.candlepin.model.Release;
import org.candlepin.model.activationkeys.ActivationKey;
import org.candlepin.model.activationkeys.ActivationKeyCurator;
import org.candlepin.model.activationkeys.ActivationKeyPool;
import org.candlepin.policy.js.activationkey.ActivationKeyRules;
import org.candlepin.test.DatabaseTestFixture;
import org.candlepin.test.TestUtil;
import org.candlepin.util.ServiceLevelValidator;

import com.google.inject.Injector;

import org.junit.Before;
import org.junit.Test;
import org.xnap.commons.i18n.I18n;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;
/**
 * ActivationKeyResourceTest
 */
public class ActivationKeyResourceTest extends DatabaseTestFixture {
    @Inject private ProductCurator productCurator;
    @Inject private ActivationKeyCurator activationKeyCurator;
    @Inject private ServiceLevelValidator serviceLevelValidator;
    @Inject private I18n i18n;
    @Inject private Injector injector;

    protected ActivationKeyResource activationKeyResource;
    protected ActivationKeyRules activationKeyRules;
    private static int poolid = 0;
    private Owner owner;

    @Before
    public void setUp() {

        activationKeyResource = injector
            .getInstance(ActivationKeyResource.class);
        activationKeyRules = injector
            .getInstance(ActivationKeyRules.class);
        owner = createOwner();
    }

    @Test(expected = BadRequestException.class)
    public void testCreateReadDelete() {
        ActivationKey key = new ActivationKey();
        key.setOwner(owner);
        key.setName("dd");
        key.setServiceLevel("level1");
        key.setReleaseVer(new Release("release1"));
        activationKeyCurator.create(key);

        assertNotNull(key.getId());
        key = activationKeyResource.getActivationKey(key.getId());
        assertNotNull(key);
        key.setName("JarJarBinks");
        key.setServiceLevel("level2");
        key.setReleaseVer(new Release("release2"));
        key = activationKeyResource.updateActivationKey(key.getId(), key);
        key = activationKeyResource.getActivationKey(key.getId());
        assertEquals("JarJarBinks", key.getName());
        assertEquals("level2", key.getServiceLevel());
        assertEquals("release2", key.getReleaseVer().getReleaseVer());
        activationKeyResource.deleteActivationKey(key.getId());
        key = activationKeyResource.getActivationKey(key.getId());
    }

    @Test(expected = BadRequestException.class)
    public void testInvalidTokenIdOnDelete() {
        activationKeyResource.deleteActivationKey("JarJarBinks");
    }

    @Test
    public void testAddingRemovingPools() {
        ActivationKey key = new ActivationKey();
        Product product = TestUtil.createProduct(owner);
        productCurator.create(product);
        Pool pool = createPoolAndSub(owner, product, 10L, new Date(), new Date());
        key.setOwner(owner);
        key.setName("dd");
        key = activationKeyCurator.create(key);
        assertNotNull(key.getId());
        activationKeyResource.addPoolToKey(key.getId(), pool.getId(), 1L);
        assertTrue(key.getPools().size() == 1);
        activationKeyResource.removePoolFromKey(key.getId(), pool.getId());
        assertTrue(key.getPools().size() == 0);
    }

    @Test(expected = BadRequestException.class)
    public void testReaddingPools() {
        ActivationKey key = new ActivationKey();
        Product product = TestUtil.createProduct(owner);
        productCurator.create(product);
        Pool pool = createPoolAndSub(owner, product, 10L, new Date(), new Date());

        key.setOwner(owner);
        key.setName("dd");
        key = activationKeyCurator.create(key);

        assertNotNull(key.getId());

        activationKeyResource.addPoolToKey(key.getId(), pool.getId(), 1L);
        assertTrue(key.getPools().size() == 1);

        activationKeyResource.addPoolToKey(key.getId(), pool.getId(), 1L);
        // ^ Kaboom.
    }

    @Test(expected = BadRequestException.class)
    public void testActivationKeyWithNonMultiPool() {
        ActivationKey ak = genActivationKey();
        ActivationKeyCurator akc = mock(ActivationKeyCurator.class);
        Pool p = genPool();
        p.getProduct().setAttribute("multi-entitlement", "no");
        PoolManager poolManager = mock(PoolManager.class);

        when(akc.verifyAndLookupKey(eq("testKey"))).thenReturn(ak);
        when(poolManager.find(eq("testPool"))).thenReturn(p);

        ActivationKeyResource akr = new ActivationKeyResource(
            akc, i18n, poolManager, serviceLevelValidator, activationKeyRules, null);
        akr.addPoolToKey("testKey", "testPool", 2L);
    }

    @Test(expected = BadRequestException.class)
    public void testActivationKeyWithNegPoolQuantity() {
        ActivationKey ak = genActivationKey();
        ActivationKeyCurator akc = mock(ActivationKeyCurator.class);
        Pool p = genPool();
        p.setQuantity(10L);
        PoolManager poolManager = mock(PoolManager.class);

        when(akc.verifyAndLookupKey(eq("testKey"))).thenReturn(ak);
        when(poolManager.find(eq("testPool"))).thenReturn(p);

        ActivationKeyResource akr = new ActivationKeyResource(
            akc, i18n, poolManager, serviceLevelValidator, activationKeyRules, null);
        akr.addPoolToKey("testKey", "testPool", -3L);
    }

    @Test
    public void testActivationKeyWithLargePoolQuantity() {
        ActivationKey ak = genActivationKey();
        ActivationKeyCurator akc = mock(ActivationKeyCurator.class);
        Pool p = genPool();
        p.getProduct().setAttribute("multi-entitlement", "yes");
        p.setQuantity(10L);
        PoolManager poolManager = mock(PoolManager.class);

        when(akc.verifyAndLookupKey(eq("testKey"))).thenReturn(ak);
        when(poolManager.find(eq("testPool"))).thenReturn(p);

        ActivationKeyResource akr = new ActivationKeyResource(
            akc, i18n, poolManager, serviceLevelValidator, activationKeyRules, null);
        akr.addPoolToKey("testKey", "testPool", 15L);
    }

    @Test
    public void testActivationKeyWithUnlimitedPool() {
        ActivationKey ak = genActivationKey();
        ActivationKeyCurator akc = mock(ActivationKeyCurator.class);
        Pool p = genPool();
        p.getProduct().setAttribute("multi-entitlement", "yes");
        p.setQuantity(-1L);
        PoolManager poolManager = mock(PoolManager.class);

        when(akc.verifyAndLookupKey(eq("testKey"))).thenReturn(ak);
        when(poolManager.find(eq("testPool"))).thenReturn(p);

        ActivationKeyResource akr = new ActivationKeyResource(
            akc, i18n, poolManager, serviceLevelValidator, activationKeyRules, null);
        akr.addPoolToKey("testKey", "testPool", 15L);
    }


    @Test(expected = BadRequestException.class)
    public void testActivationKeyWithPersonConsumerType() {
        ActivationKey ak = genActivationKey();
        ActivationKeyCurator akc = mock(ActivationKeyCurator.class);
        Pool p = genPool();
        p.getProduct().setAttribute("requires_consumer_type", "person");
        p.setQuantity(1L);
        PoolManager poolManager = mock(PoolManager.class);

        when(akc.verifyAndLookupKey(eq("testKey"))).thenReturn(ak);
        when(poolManager.find(eq("testPool"))).thenReturn(p);

        ActivationKeyResource akr = new ActivationKeyResource(
            akc, i18n, poolManager, serviceLevelValidator, activationKeyRules, null);
        akr.addPoolToKey("testKey", "testPool", 1L);
    }

    @Test
    public void testActivationKeyWithNonPersonConsumerType() {
        ActivationKey ak = genActivationKey();
        ActivationKeyCurator akc = mock(ActivationKeyCurator.class);
        Pool p = genPool();
        p.getProduct().setAttribute("requires_consumer_type", "candlepin");
        PoolManager poolManager = mock(PoolManager.class);

        when(akc.verifyAndLookupKey(eq("testKey"))).thenReturn(ak);
        when(poolManager.find(eq("testPool"))).thenReturn(p);

        ActivationKeyResource akr = new ActivationKeyResource(
            akc, i18n, poolManager, serviceLevelValidator, activationKeyRules, null);
        assertNotNull(akr.addPoolToKey("testKey", "testPool", 1L));
    }

    @Test
    public void testActivationKeyWithSameHostReqPools() {
        ActivationKey ak = genActivationKey();
        ActivationKeyCurator akc = mock(ActivationKeyCurator.class);
        PoolManager poolManager = mock(PoolManager.class);
        Pool p1 = genPool();
        p1.setAttribute("requires_host", "host1");
        Pool p2 = genPool();
        p2.setAttribute("requires_host", "host1");

        when(akc.verifyAndLookupKey(eq("testKey"))).thenReturn(ak);
        when(poolManager.find(eq("testPool1"))).thenReturn(p1);
        when(poolManager.find(eq("testPool2"))).thenReturn(p2);

        ActivationKeyResource akr = new ActivationKeyResource(
            akc, i18n, poolManager, serviceLevelValidator, activationKeyRules, null);

        akr.addPoolToKey("testKey", "testPool1", 1L);
        assertEquals(1, ak.getPools().size());
        Set<ActivationKeyPool> akPools = new HashSet<ActivationKeyPool>();
        akPools.add(new ActivationKeyPool(ak, p1, 1L));
        ak.setPools(akPools);
        akr.addPoolToKey("testKey", "testPool2", 1L);
        assertEquals(2, ak.getPools().size());
    }

    @Test
    public void testActivationKeyWithDiffHostReqPools() {
        ActivationKey ak = genActivationKey();
        ActivationKeyCurator akc = mock(ActivationKeyCurator.class);
        PoolManager poolManager = mock(PoolManager.class);
        Pool p1 = genPool();
        p1.setAttribute("requires_host", "host1");
        Pool p2 = genPool();
        p2.setAttribute("requires_host", "host2");

        ak.addPool(p2, 1L);
        when(akc.verifyAndLookupKey(eq("testKey"))).thenReturn(ak);
        when(poolManager.find(eq("testPool1"))).thenReturn(p1);

        ActivationKeyResource akr = new ActivationKeyResource(
            akc, i18n, poolManager, serviceLevelValidator, activationKeyRules, null);
        akr.addPoolToKey("testKey", "testPool1", 1L);
    }

    @Test
    public void testActivationKeyHostReqPoolThenNonHostReq() {
        ActivationKey ak = genActivationKey();
        ActivationKeyCurator akc = mock(ActivationKeyCurator.class);
        PoolManager poolManager = mock(PoolManager.class);
        Pool p1 = genPool();
        p1.setAttribute("requires_host", "host1");
        Pool p2 = genPool();
        p2.setAttribute("requires_host", "");

        when(akc.verifyAndLookupKey(eq("testKey"))).thenReturn(ak);
        when(poolManager.find(eq("testPool1"))).thenReturn(p1);
        when(poolManager.find(eq("testPool2"))).thenReturn(p2);

        ActivationKeyResource akr = new ActivationKeyResource(
            akc, i18n, poolManager, serviceLevelValidator, activationKeyRules, null);
        akr.addPoolToKey("testKey", "testPool1", 1L);
        assertEquals(1, ak.getPools().size());
        ak.addPool(p1, 1L);
        akr.addPoolToKey("testKey", "testPool2", 1L);
        assertEquals(3, ak.getPools().size());
    }

    @Test
    public void testActivationKeyWithNullQuantity() {
        ActivationKey ak = genActivationKey();
        ActivationKeyCurator akc = mock(ActivationKeyCurator.class);
        Pool p = genPool();
        PoolManager poolManager = mock(PoolManager.class);

        when(akc.verifyAndLookupKey(eq("testKey"))).thenReturn(ak);
        when(poolManager.find(eq("testPool"))).thenReturn(p);

        ActivationKeyResource akr = new ActivationKeyResource(
            akc, i18n, poolManager, serviceLevelValidator, activationKeyRules, null);
        akr.addPoolToKey("testKey", "testPool", null);
    }

    @Test(expected = BadRequestException.class)
    public void testUpdateTooLongRelease() {
        ActivationKey key = new ActivationKey();
        Owner owner = createOwner();
        key.setOwner(owner);
        key.setName("dd");
        key.setServiceLevel("level1");
        key.setReleaseVer(new Release("release1"));
        activationKeyCurator.create(key);

        ActivationKey key2 = new ActivationKey();
        key2.setOwner(owner);
        key2.setName("dd");
        key2.setServiceLevel("level1");
        key2.setReleaseVer(new Release(TestUtil.getStringOfSize(256)));
        key = activationKeyResource.updateActivationKey(key.getId(), key2);
    }

    @Test
    public void testAddingRemovingProductIDs() {
        ActivationKey key = new ActivationKey();
        Owner owner = createOwner();
        Product product = TestUtil.createProduct(owner);
        productCurator.create(product);

        key.setOwner(owner);
        key.setName("dd");
        key = activationKeyCurator.create(key);

        assertNotNull(key.getId());
        activationKeyResource.addProductIdToKey(key.getId(), product.getId());
        assertTrue(key.getProducts().size() == 1);
        activationKeyResource.removeProductIdFromKey(key.getId(), product.getId());
        assertEquals(0, key.getProducts().size());
    }

    @Test(expected = BadRequestException.class)
    public void testReaddingProductIDs() {
        ActivationKey key = new ActivationKey();
        Owner owner = createOwner();
        Product product = TestUtil.createProduct(owner);
        productCurator.create(product);

        key.setOwner(owner);
        key.setName("dd");
        key = activationKeyCurator.create(key);

        assertNotNull(key.getId());

        activationKeyResource.addProductIdToKey(key.getId(), product.getId());
        assertEquals(1, key.getProducts().size());

        activationKeyResource.addProductIdToKey(key.getId(), product.getId());
        // ^ Kaboom.
    }

    private Pool genPool() {
        Pool pool = new Pool();
        pool.setId("" + poolid++);
        pool.setQuantity(10L);
        pool.setConsumed(4L);
        pool.setAttribute("multi-entitlement", "yes");
        pool.setProduct(TestUtil.createProduct(owner));
        return pool;
    }

    private ActivationKey genActivationKey() {
        return new ActivationKey();
    }
}
