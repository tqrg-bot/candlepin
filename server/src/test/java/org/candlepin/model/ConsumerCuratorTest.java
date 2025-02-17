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

import org.candlepin.common.config.Configuration;
import org.candlepin.common.exceptions.NotFoundException;
import org.candlepin.config.ConfigProperties;
import org.candlepin.model.ConsumerType.ConsumerTypeEnum;
import org.candlepin.resource.util.ResourceDateParser;
import org.candlepin.test.DatabaseTestFixture;
import org.candlepin.util.Util;

import org.junit.Before;
import org.junit.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.persistence.EntityManager;

/**
 * ConsumerCuratorTest JUnit tests for Consumer database code
 */
public class ConsumerCuratorTest extends DatabaseTestFixture {
    @Inject private OwnerCurator ownerCurator;
    @Inject private ProductCurator productCurator;
    @Inject private ConsumerCurator consumerCurator;
    @Inject private ConsumerTypeCurator consumerTypeCurator;
    @Inject private EntitlementCurator entitlementCurator;
    @Inject private Configuration config;
    @Inject private DeletedConsumerCurator dcc;
    @Inject private EntityManager em;

    private Owner owner;
    private ConsumerType ct;
    private Consumer factConsumer;

    @Before
    public void setUp() {
        owner = new Owner("test-owner", "Test Owner");
        owner = ownerCurator.create(owner);
        ct = new ConsumerType(ConsumerTypeEnum.SYSTEM);
        ct = consumerTypeCurator.create(ct);

        config.setProperty(ConfigProperties.INTEGER_FACTS,
            "system.count, system.multiplier");
        config.setProperty(ConfigProperties.NON_NEG_INTEGER_FACTS, "system.count");

        factConsumer = new Consumer("a consumer", "username", owner, ct);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void normalCreate() {
        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumerCurator.create(consumer);

        List<Product> results = entityManager().createQuery(
                "select c from Consumer as c").getResultList();
        assertEquals(1, results.size());
    }

    @Test
    public void addGuestConsumers() {
        Consumer consumer = new Consumer("hostConsumer", "testUser", owner, ct);
        consumerCurator.create(consumer);
        Consumer gConsumer1 = new Consumer("guestConsumer1", "testUser", owner, ct);
        gConsumer1.getFacts().put("virt.uuid", "test-guest-1");
        consumerCurator.create(gConsumer1);
        Consumer gConsumer2 = new Consumer("guestConsumer2", "testUser", owner, ct);
        gConsumer2.getFacts().put("virt.uuid", "test-guest-2");
        consumerCurator.create(gConsumer2);
        consumer.addGuestId(new GuestId("test-guest-1"));
        consumer.addGuestId(new GuestId("test-guest-2"));
        consumer.addGuestIdCheckIn();
        consumerCurator.update(consumer);

        List<Consumer> guests = consumerCurator.getGuests(consumer);
        assertTrue(guests.size() == 2);
    }

    @Test
    public void addGuestConsumersReversedEndianGuestId() {
        Consumer consumer = new Consumer("hostConsumer", "testUser", owner, ct);
        consumerCurator.create(consumer);
        Consumer gConsumer1 = new Consumer("guestConsumer1", "testUser", owner, ct);
        gConsumer1.getFacts().put("virt.uuid", "06F81B41-AAC0-7685-FBE9-79AA4A326511");
        consumerCurator.create(gConsumer1);
        Consumer gConsumer2 = new Consumer("guestConsumer2", "testUser", owner, ct);
        gConsumer2.getFacts().put("virt.uuid", "4C4C4544-0046-4210-8031-C7C04F445831");
        consumerCurator.create(gConsumer2);
        // Reversed endian, first 3 sections
        consumer.addGuestId(new GuestId("411bf806-c0aa-8576-fbe9-79aa4a326511"));
        // matches a guests facts, case insensitive
        consumer.addGuestId(new GuestId("4c4c4544-0046-4210-8031-c7c04f445831"));
        // Doesn't match a registered guest consumer
        consumer.addGuestId(new GuestId("43e41def-e9ae-4b6b-b8f4-942c8b69a39e"));
        consumer.addGuestIdCheckIn();
        consumerCurator.update(consumer);

        List<Consumer> guests = consumerCurator.getGuests(consumer);
        assertTrue(guests.size() == 2);
    }

    @Test
    public void caseInsensitiveVirtUuidMatching() {
        Consumer host = new Consumer("hostConsumer", "testUser", owner, ct);
        consumerCurator.create(host);

        Consumer gConsumer1 = new Consumer("guestConsumer1", "testUser", owner, ct);
        gConsumer1.getFacts().put("virt.uuid", "daf0fe10-956b-7b4e-b7dc-b383ce681ba8");
        consumerCurator.create(gConsumer1);

        host.addGuestId(new GuestId("DAF0FE10-956B-7B4E-B7DC-B383CE681BA8"));
        host.addGuestIdCheckIn();
        consumerCurator.update(host);

        List<Consumer> guests = consumerCurator.getGuests(host);
        assertTrue(guests.size() == 1);
    }

    @Test
    public void caseInsensitiveVirtUuidMatchingDifferentOwners() {
        Consumer host = new Consumer("hostConsumer", "testUser", owner, ct);
        consumerCurator.create(host);

        owner = new Owner("test-owner2", "Test Owner2");
        owner = ownerCurator.create(owner);

        Consumer gConsumer1 = new Consumer("guestConsumer1", "testUser", owner, ct);
        gConsumer1.getFacts().put("virt.uuid", "daf0fe10-956b-7b4e-b7dc-b383ce681ba8");
        consumerCurator.create(gConsumer1);

        host.addGuestId(new GuestId("DAF0FE10-956B-7B4E-B7DC-B383CE681BA8"));
        consumerCurator.update(host);

        List<Consumer> guests = consumerCurator.getGuests(host);
        assertTrue(guests.size() == 0);
    }

    @Test
    public void addGuestsNotConsumers() {
        Consumer consumer = new Consumer("hostConsumer", "testUser", owner, ct);
        consumerCurator.create(consumer);
        consumer.addGuestId(new GuestId("test-guest-1"));
        consumer.addGuestId(new GuestId("test-guest-2"));
        consumerCurator.update(consumer);

        List<Consumer> guests = consumerCurator.getGuests(consumer);
        assertTrue(guests.size() == 0);
    }

    @Test
    public void getGuestConsumerSharedId() throws Exception {
        Consumer hConsumer1 = new Consumer("hostConsumer1", "testUser", owner, ct);
        consumerCurator.create(hConsumer1);
        Consumer hConsumer2 = new Consumer("hostConsumer2", "testUser", owner, ct);
        consumerCurator.create(hConsumer2);
        Consumer gConsumer1 = new Consumer("guestConsumer1", "testUser", owner, ct);
        gConsumer1.getFacts().put("virt.uuid", "test-guest-1");
        consumerCurator.create(gConsumer1);

        // This can happen so fast the consumers end up with the same update time,
        // it's 5 milliseconds, deal with it.
        Thread.sleep(5);

        Consumer gConsumer2 = new Consumer("guestConsumer2", "testUser", owner, ct);
        gConsumer2.getFacts().put("virt.uuid", "test-guest-1");
        consumerCurator.create(gConsumer2);

        GuestId hGuest1 = new GuestId("test-guest-1");
        hConsumer1.addGuestId(hGuest1);
        hConsumer1.addGuestIdCheckIn();
        consumerCurator.update(hConsumer1);

        // Uppercase the guest ID reported by host 2 just to make sure the casing is
        // working properly here too:
        GuestId hGuest2 = new GuestId("TEST-GUEST-1");
        hConsumer2.addGuestId(hGuest2);
        hConsumer2.addGuestIdCheckIn();
        consumerCurator.update(hConsumer2);

        List<Consumer> guests1 = consumerCurator.getGuests(hConsumer1);
        List<Consumer> guests2 = consumerCurator.getGuests(hConsumer2);
        assertTrue(hGuest1.getUpdated().before(hGuest2.getUpdated()));
        assertEquals(0, guests1.size());
        assertEquals(1, guests2.size());
        assertEquals("guestConsumer2", guests2.get(0).getName());
    }

    @Test
    public void noHostRegistered() {
        Consumer host = consumerCurator.getHost("system-uuid-for-guest", owner);
        assertTrue(host == null);
    }

    @Test
    public void oneHostRegistered() {
        Consumer host = new Consumer("hostConsumer", "testUser", owner, ct);
        consumerCurator.create(host);

        Consumer gConsumer1 = new Consumer("guestConsumer1", "testUser", owner, ct);
        gConsumer1.getFacts().put("virt.uuid", "daf0fe10-956b-7b4e-b7dc-b383ce681ba8");
        consumerCurator.create(gConsumer1);

        host.addGuestId(new GuestId("DAF0FE10-956B-7B4E-B7DC-B383CE681BA8"));
        host.addGuestIdCheckIn();
        consumerCurator.update(host);

        Consumer guestHost = consumerCurator.getHost(
            "daf0fe10-956b-7b4e-b7dc-b383ce681ba8", owner);
        assertEquals(host, guestHost);
    }

    @Test
    public void oneHostRegisteredReverseEndian() {
        Consumer host = new Consumer("hostConsumer", "testUser", owner, ct);
        consumerCurator.create(host);

        Consumer gConsumer1 = new Consumer("guestConsumer1", "testUser", owner, ct);
        gConsumer1.getFacts().put("virt.uuid", "daf0fe10-956b-7b4e-b7dc-b383ce681ba8");
        consumerCurator.create(gConsumer1);

        host.addGuestId(new GuestId("DAF0FE10-956B-7B4E-B7DC-B383CE681BA8"));
        host.addGuestIdCheckIn();
        consumerCurator.update(host);

        Consumer guestHost = consumerCurator.getHost(
            "10fef0da-6b95-4e7b-b7dc-b383ce681ba8", owner);
        assertEquals(host, guestHost);
    }

    @Test
    public void twoHostsRegisteredPickSecond() {
        Consumer host1 = new Consumer("hostConsumer", "testUser", owner, ct);
        consumerCurator.create(host1);

        Consumer host2 = new Consumer("hostConsumer2", "testUser2", owner, ct);
        consumerCurator.create(host2);

        Consumer gConsumer1 = new Consumer("guestConsumer1", "testUser", owner, ct);
        gConsumer1.getFacts().put("virt.uuid", "daf0fe10-956b-7b4e-b7dc-b383ce681ba8");
        consumerCurator.create(gConsumer1);

        GuestId host1Guest = new GuestId("DAF0FE10-956B-7B4E-B7DC-B383CE681BA8");
        host1.addGuestId(host1Guest);
        host1.addGuestIdCheckIn();
        consumerCurator.update(host1);

        GuestId host2Guest = new GuestId("DAF0FE10-956B-7B4E-B7DC-B383CE681BA8");
        host2.addGuestId(host2Guest);
        host2.addGuestIdCheckIn();
        consumerCurator.update(host2);

        Consumer guestHost = consumerCurator.getHost(
            "daf0fe10-956b-7b4e-b7dc-b383ce681ba8", owner);
        assertTrue(host1Guest.getUpdated().before(host2Guest.getUpdated()));
        assertEquals(host2.getUuid(), guestHost.getUuid());
    }

    @Test
    public void twoHostsRegisteredPickFirst() {
        Consumer host1 = new Consumer("hostConsumer", "testUser", owner, ct);
        consumerCurator.create(host1);

        Consumer host2 = new Consumer("hostConsumer2", "testUser2", owner, ct);
        consumerCurator.create(host2);

        Consumer gConsumer1 = new Consumer("guestConsumer1", "testUser", owner, ct);
        gConsumer1.getFacts().put("virt.uuid", "daf0fe10-956b-7b4e-b7dc-b383ce681ba8");
        consumerCurator.create(gConsumer1);

        GuestId host2Guest = new GuestId("DAF0FE10-956B-7B4E-B7DC-B383CE681BA8");
        host2.addGuestId(host2Guest);
        host2.addGuestIdCheckIn();
        consumerCurator.update(host2);

        GuestId host1Guest = new GuestId("DAF0FE10-956B-7B4E-B7DC-B383CE681BA8");
        host1.addGuestId(host1Guest);
        host1.addGuestIdCheckIn();
        consumerCurator.update(host1);

        Consumer guestHost = consumerCurator.getHost(
            "daf0fe10-956b-7b4e-b7dc-b383ce681ba8", owner);
        assertTrue(host1Guest.getUpdated().after(host2Guest.getUpdated()));
        assertEquals(host1.getUuid(), guestHost.getUuid());
    }

    @Test
    public void noGuestsRegistered() {
        Consumer consumer = new Consumer("hostConsumer", "testUser", owner, ct);
        consumer = consumerCurator.create(consumer);

        List<Consumer> guests = consumerCurator.getGuests(consumer);
        assertTrue(guests.size() == 0);
    }

    @Test
    public void getGuestsHostMapChoosestLatestReporter() {
        Consumer host1 = new Consumer("hostConsumer", "testUser", owner, ct);
        consumerCurator.create(host1);

        Consumer host2 = new Consumer("hostConsumer2", "testUser2", owner, ct);
        consumerCurator.create(host2);

        String virtUuid1 = "daf0fe10-956b-7b4e-b7dc-b383ce681ba8"; // on both hosts
        String virtUuid2 = "faf0fe10-956b-7b4e-b7dc-b383ce681cc9"; // only on host 1
        String virtUuid3 = "daf0fe10-956b-7b4e-b7dc-b383ce681ff8"; // only on host2
        addGuestIdsTo(host2, virtUuid1, virtUuid3);
        addGuestIdsTo(host1, virtUuid1, virtUuid2);

        List<String> guestIds = new LinkedList<String>();
        guestIds.add(virtUuid1);
        guestIds.add(virtUuid2);
        guestIds.add(virtUuid3);

        VirtConsumerMap results = consumerCurator.getGuestsHostMap(owner, guestIds);
        assertEquals(host1.getUuid(), results.get(virtUuid1.toUpperCase()).getUuid());
        assertEquals(host1.getUuid(), results.get(virtUuid2.toUpperCase()).getUuid());
        assertEquals(host2.getUuid(), results.get(virtUuid3.toUpperCase()).getUuid());
    }

    private void addGuestIdsTo(Consumer host, String... virtUuids) {
        for (String virt : virtUuids) {
            GuestId gid = new GuestId(virt.toUpperCase());
            host.addGuestId(gid);
        }
        host.addGuestIdCheckIn();
        consumerCurator.update(host);
    }

    @Test
    public void updateCheckinTime() {
        Consumer consumer = new Consumer("hostConsumer", "testUser", owner, ct);
        consumer = consumerCurator.create(consumer);
        Date dt = ResourceDateParser.parseDateString("2011-09-26T18:10:50.184081+00:00");
        consumerCurator.updateLastCheckin(consumer, dt);
        consumerCurator.refresh(consumer);
        consumer = consumerCurator.find(consumer.getId());

        assertEquals(consumer.getLastCheckin().getTime(), dt.getTime());
    }
    @Test
    public void updatelastCheckin() throws Exception {
        Date date = new Date();
        Consumer consumer = new Consumer("hostConsumer", "testUser", owner, ct);
        consumer.addCheckIn(date);
        Thread.sleep(5); // sleep for at 5ms to allow enough time to pass
        consumer = consumerCurator.create(consumer);
        consumerCurator.updateLastCheckin(consumer);
        consumerCurator.refresh(consumer);
        assertTrue(consumer.getLastCheckin().getTime() > date.getTime());
    }

    @Test
    public void delete() {
        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumer = consumerCurator.create(consumer);
        String cid = consumer.getUuid();

        consumerCurator.delete(consumer);
        assertEquals(1, dcc.countByConsumerUuid(cid));
        DeletedConsumer dc = dcc.findByConsumerUuid(cid);

        assertEquals(cid, dc.getConsumerUuid());
        assertEquals(owner.getId(), dc.getOwnerId());
    }

    @Test
    public void deleteTwice() {
        // attempt to create and delete the same consumer uuid twice
        Owner altOwner = new Owner("test-owner2", "Test Owner2");

        altOwner = ownerCurator.create(altOwner);

        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumer.setUuid("Doppelganger");
        consumer = consumerCurator.create(consumer);

        consumerCurator.delete(consumer);
        DeletedConsumer dc = dcc.findByConsumerUuid("Doppelganger");
        Date deletionDate1 = dc.getUpdated();

        consumer = new Consumer("testConsumer", "testUser", altOwner, ct);
        consumer.setUuid("Doppelganger");
        consumer = consumerCurator.create(consumer);
        consumerCurator.delete(consumer);
        dc = dcc.findByConsumerUuid("Doppelganger");
        Date deletionDate2 = dc.getUpdated();
        assertEquals(-1, deletionDate1.compareTo(deletionDate2));
        assertEquals(altOwner.getId(), dc.getOwnerId());
    }

    @Test
    public void testConsumerFactsVerifySuccess() {
        Map<String, String> facts = new HashMap<String, String>();
        facts.put("system.count", "3");
        facts.put("system.multiplier", "-2");

        factConsumer.setFacts(facts);

        factConsumer = consumerCurator.create(factConsumer);
        assertEquals(consumerCurator.findByUuid(factConsumer.getUuid()), factConsumer);
        assertEquals(factConsumer.getFact("system.count"), "3");
        assertEquals(factConsumer.getFact("system.multiplier"), "-2");
    }

    @Test
    public void testConsumerFactsVerifyBadInt() {
        Map<String, String> facts = new HashMap<String, String>();
        facts.put("system.count", "zzz");
        facts.put("system.multiplier", "-2");

        factConsumer.setFacts(facts);
        factConsumer = consumerCurator.create(factConsumer);
        assertEquals(factConsumer.getFact("system.count"), null);
        assertEquals(factConsumer.getFact("system.multiplier"), "-2");
    }

    @Test
    public void testConsumerFactsVerifyBadPositive() {
        Map<String, String> facts = new HashMap<String, String>();
        facts.put("system.count", "-2");
        facts.put("system.multiplier", "-2");

        factConsumer.setFacts(facts);
        factConsumer = consumerCurator.create(factConsumer);
        assertEquals(factConsumer.getFact("system.count"), null);
        assertEquals(factConsumer.getFact("system.multiplier"), "-2");
    }

    @Test
    public void testConsumerFactsVerifyBadUpdateValue() {
        Map<String, String> facts = new HashMap<String, String>();
        facts.put("system.count", "3");
        facts.put("system.multiplier", "-2");

        factConsumer.setFacts(facts);
        factConsumer = consumerCurator.create(factConsumer);
        assertEquals(consumerCurator.findByUuid(factConsumer.getUuid()), factConsumer);
        assertEquals(factConsumer.getFact("system.count"), "3");
        assertEquals(factConsumer.getFact("system.multiplier"), "-2");

        factConsumer.setFact("system.count", "sss");
        factConsumer = consumerCurator.update(factConsumer);
        assertEquals(factConsumer.getFact("system.count"), null);
        assertEquals(factConsumer.getFact("system.multiplier"), "-2");
    }

    @Test
    public void testSubstringConfigList() {
        Map<String, String> facts = new HashMap<String, String>();
        facts.put("system.cou", "this should not be checked");

        factConsumer.setFacts(facts);
        factConsumer = consumerCurator.create(factConsumer);
        assertEquals(consumerCurator.findByUuid(factConsumer.getUuid()), factConsumer);
    }

    @Test
    public void testFindByUuids() {
        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumer.setUuid("1");
        consumer = consumerCurator.create(consumer);

        Consumer consumer2 = new Consumer("testConsumer2", "testUser2", owner, ct);
        consumer2.setUuid("2");
        consumer2 = consumerCurator.create(consumer2);

        Consumer consumer3 = new Consumer("testConsumer3", "testUser3", owner, ct);
        consumer3.setUuid("3");
        consumer3 = consumerCurator.create(consumer3);

        List<Consumer> results = consumerCurator.findByUuids(
            Arrays.asList(new String[] {"1", "2"}));
        assertTrue(results.contains(consumer));
        assertTrue(results.contains(consumer2));
        assertFalse(results.contains(consumer3));
    }

    @Test
    public void testFindByUuidsAndOwner() {
        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumer.setUuid("1");
        consumer = consumerCurator.create(consumer);

        Owner owner2 = new Owner("test-owner2", "Test Owner2");
        ownerCurator.create(owner2);

        Consumer consumer2 = new Consumer("testConsumer2", "testUser2", owner2, ct);
        consumer2.setUuid("2");
        consumer2 = consumerCurator.create(consumer2);

        Consumer consumer3 = new Consumer("testConsumer3", "testUser3", owner2, ct);
        consumer3.setUuid("3");
        consumer3 = consumerCurator.create(consumer3);

        List<Consumer> results = consumerCurator.findByUuidsAndOwner(
            Arrays.asList(new String[] {"2"}), owner2);
        assertTrue(results.contains(consumer2));
        assertFalse(results.contains(consumer));
        assertFalse(results.contains(consumer3));
    }

    @Test
    public void getGuestConsumerMap() {

        String guestId1 = "06F81B41-AAC0-7685-FBE9-79AA4A326511";
        String guestId1ReverseEndian = "411bf806-c0aa-8576-fbe9-79aa4a326511";
        Consumer gConsumer1 = new Consumer("guestConsumer1", "testUser", owner, ct);
        gConsumer1.getFacts().put("virt.uuid", guestId1);
        consumerCurator.create(gConsumer1);

        String guestId2 = "4C4C4544-0046-4210-8031-C7C04F445831";
        Consumer gConsumer2 = new Consumer("guestConsumer2", "testUser", owner, ct);
        gConsumer2.getFacts().put("virt.uuid", guestId2);
        consumerCurator.create(gConsumer2);

        List<String> guestIds = new LinkedList<String>();
        guestIds.add(guestId1ReverseEndian); // reversed endian match
        guestIds.add(guestId2); // direct match
        VirtConsumerMap guestMap = consumerCurator.getGuestConsumersMap(
                owner, guestIds);

        assertEquals(2, guestMap.size());

        assertEquals(gConsumer1.getId(), guestMap.get(
                guestId1.toLowerCase()).getId());
        assertEquals(gConsumer1.getId(), guestMap.get(
                guestId1ReverseEndian).getId());

        assertEquals(gConsumer2.getId(), guestMap.get(
                guestId2.toLowerCase()).getId());
        assertEquals(gConsumer2.getId(), guestMap.get(
                Util.transformUuid(guestId2.toLowerCase())).getId());
    }

    @Test
    public void testDoesConsumerExistNo() {
        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumer.setUuid("1");
        consumer = consumerCurator.create(consumer);
        boolean result = consumerCurator.doesConsumerExist("unknown");
        assertFalse(result);
    }

    @Test
    public void testDoesConsumerExistYes() {
        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumer.setUuid("1");
        consumer = consumerCurator.create(consumer);
        boolean result = consumerCurator.doesConsumerExist("1");
        assertTrue(result);
    }

    @Test
    public void testFindByUuid() {
        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumer.setUuid("1");
        consumer = consumerCurator.create(consumer);

        Consumer result = consumerCurator.findByUuid("1");
        assertEquals(result, consumer);
    }

    @Test
    public void testFindByUuidDoesntMatch() {
        Consumer result = consumerCurator.findByUuid("1");
        assertNull(result);
    }

    @Test
    public void testVerifyAndLookupConsumer() {
        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumer.setUuid("1");
        consumer = consumerCurator.create(consumer);

        Consumer result = consumerCurator.verifyAndLookupConsumer("1");
        assertEquals(result, consumer);
    }

    @Test(expected = NotFoundException.class)
    public void testVerifyAndLookupConsumerDoesntMatch() {
        Consumer result = consumerCurator.verifyAndLookupConsumer("1");
        assertNull(result);
    }

    @Test
    public void testGetHypervisor() {
        String hypervisorid = "hypervisor";
        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumer.setHypervisorId(new HypervisorId(hypervisorid));
        consumer = consumerCurator.create(consumer);
        Consumer result = consumerCurator.getHypervisor(hypervisorid, owner);
        assertEquals(consumer, result);
    }

    @Test
    public void testGetHypervisorWrongOwner() {
        Owner otherOwner = new Owner("test-owner-other", "Test Other Owner");
        otherOwner = ownerCurator.create(otherOwner);
        String hypervisorid = "hypervisor";
        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumer.setHypervisorId(new HypervisorId(hypervisorid));
        consumer = consumerCurator.create(consumer);
        Consumer result = consumerCurator.getHypervisor(hypervisorid, otherOwner);
        assertNull(result);
    }

    @Test
    public void testGetHypervisorConsumerMap() {
        String hypervisorId1 = "Hypervisor";
        Consumer consumer1 = new Consumer("testConsumer", "testUser", owner, ct);
        consumer1.setHypervisorId(new HypervisorId(hypervisorId1));
        consumer1 = consumerCurator.create(consumer1);

        String hypervisorId2 = "hyPERvisor2";
        Consumer consumer2 = new Consumer("testConsumer", "testUser", owner, ct);
        consumer2.setHypervisorId(new HypervisorId(hypervisorId2));
        consumer2 = consumerCurator.create(consumer2);

        List<String> hypervisorIds = new LinkedList<String>();
        hypervisorIds.add(hypervisorId1);
        hypervisorIds.add(hypervisorId2);
        hypervisorIds.add("not really a hypervisor");

        VirtConsumerMap hypervisorMap =
                consumerCurator.getHostConsumersMap(owner, hypervisorIds);
        assertEquals(2, hypervisorMap.size());
        assertEquals(consumer1.getId(), hypervisorMap.get(hypervisorId1).getId());
        assertEquals(consumer2.getId(), hypervisorMap.get(hypervisorId2).getId());
    }

    @Test
    public void testGetHypervisorsBulk() {
        String hypervisorid = "hypervisor";
        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumer.setHypervisorId(new HypervisorId(hypervisorid));
        consumer = consumerCurator.create(consumer);
        List<String> hypervisorIds = new LinkedList<String>();
        hypervisorIds.add(hypervisorid);
        hypervisorIds.add("not really a hypervisor");
        List<Consumer> results = consumerCurator.getHypervisorsBulk(
            hypervisorIds, owner.getKey());
        assertEquals(1, results.size());
        assertEquals(consumer, results.get(0));
    }

    @Test
    public void testGetHypervisorsBulkEmpty() {
        String hypervisorid = "hypervisor";
        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumer.setHypervisorId(new HypervisorId(hypervisorid));
        consumer = consumerCurator.create(consumer);
        List<Consumer> results = consumerCurator.getHypervisorsBulk(
            new LinkedList<String>(), owner.getKey());
        assertEquals(0, results.size());
    }

    @Test
    public void testGetHypervisorsByOwner() {
        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumer.setHypervisorId(new HypervisorId("hypervisor"));
        consumer = consumerCurator.create(consumer);
        Owner otherOwner = ownerCurator.create(new Owner("other owner"));
        Consumer consumer2 = new Consumer("testConsumer2", "testUser2", otherOwner, ct);
        consumer2.setHypervisorId(new HypervisorId("hypervisortwo"));
        consumer2 = consumerCurator.create(consumer2);
        Consumer nonHypervisor = new Consumer("testConsumer3", "testUser3", owner, ct);
        nonHypervisor = consumerCurator.create(nonHypervisor);
        List<Consumer> results = consumerCurator.getHypervisorsForOwner(owner.getKey());
        assertEquals(1, results.size());
        assertEquals(consumer, results.get(0));
    }

    @Test
    public void testGetConsumerIdsWithStartedEnts() {
        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumerCurator.create(consumer);
        Product prod = new Product("1", "2", owner);
        this.productCurator.create(prod);
        Pool p = createPoolAndSub(owner, prod, 5L, Util.yesterday(), Util.tomorrow());
        Entitlement ent = this.createEntitlement(owner, consumer, p,
                createEntitlementCertificate("entkey", "ecert"));
        ent.setUpdatedOnStart(false);
        entitlementCurator.create(ent);

        List<String> results = consumerCurator.getConsumerIdsWithStartedEnts();
        assertEquals(1, results.size());
        assertEquals(consumer.getId(), results.get(0));
    }

    @Test
    public void testGetConsumerIdsWithStartedEntsAlreadyDone() {
        Consumer consumer = new Consumer("testConsumer", "testUser", owner, ct);
        consumerCurator.create(consumer);
        Product prod = new Product("1", "2", owner);
        this.productCurator.create(prod);
        Pool p = createPoolAndSub(owner, prod, 5L, Util.yesterday(), Util.tomorrow());
        Entitlement ent = this.createEntitlement(owner, consumer, p,
                createEntitlementCertificate("entkey", "ecert"));
        // Already taken care of
        ent.setUpdatedOnStart(true);
        entitlementCurator.create(ent);

        List<String> results = consumerCurator.getConsumerIdsWithStartedEnts();
        assertTrue(results.isEmpty());
    }

    @Test
    public void testConsumerDeleteCascadesToContentTag() {
        Consumer c = new Consumer("testConsumer", "testUser", owner, ct);
        c.setContentTags(new HashSet<String>(Arrays.asList(new String[] {"t1", "t2"})));

        String countQuery = "SELECT COUNT(*) FROM cp_consumer_content_tags";

        consumerCurator.create(c);
        BigInteger i = (BigInteger) em.createNativeQuery(countQuery).getSingleResult();
        assertEquals(new BigInteger("2"), i);

        consumerCurator.delete(c);
        i = (BigInteger) em.createNativeQuery(countQuery).getSingleResult();
        assertEquals(new BigInteger("0"), i);
    }
}
