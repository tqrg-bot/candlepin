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

import org.candlepin.common.paging.Page;
import org.candlepin.common.paging.PageRequest;
import org.candlepin.model.Pool.PoolType;
import org.candlepin.model.activationkeys.ActivationKey;
import org.candlepin.model.activationkeys.ActivationKeyPool;
import org.candlepin.policy.criteria.CriteriaRules;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.persist.Transactional;

import org.hibernate.Criteria;
import org.hibernate.Filter;
import org.hibernate.LockOptions;
import org.hibernate.Query;
import org.hibernate.ReplicationMode;
import org.hibernate.criterion.CriteriaSpecification;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.hibernate.internal.FilterImpl;
import org.hibernate.sql.JoinType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * EntitlementPoolCurator
 */
public class PoolCurator extends AbstractHibernateCurator<Pool> {

    private static Logger log = LoggerFactory.getLogger(PoolCurator.class);
    private CriteriaRules poolCriteria;
    @Inject
    protected Injector injector;

    @Inject
    public PoolCurator(CriteriaRules poolCriteria) {
        super(Pool.class);
        this.poolCriteria = poolCriteria;
    }

    @Override
    @Transactional
    public Pool find(Serializable id) {
        Pool pool = super.find(id);
        return pool;
    }

    @Override
    @Transactional
    public List<Pool> listAll() {
        List<Pool> pools = super.listAll();
        return pools;
    }

    /**
     * Returns list of pools owned by the given Owner.
     * @param o Owner to filter
     * @return pools owned by the given Owner.
     */
    @Transactional
    public List<Pool> listByOwner(Owner o) {
        return listByOwner(o, null);
    }


    @Transactional
    public List<Pool> listByOwnerAndType(Owner o, PoolType t) {
        Criteria crit = currentSession().createCriteria(Pool.class)
            .add(Restrictions.eq("owner", o))
            .add(Restrictions.eq("type", t));
        return crit.list();
    }


    /**
     * Returns list of pools owned by the given Owner.
     * @param o Owner to filter
     * @param activeOn only include pools active on the given date.
     * @return pools owned by the given Owner.
     */
    @Transactional
    public List<Pool> listByOwner(Owner o, Date activeOn) {
        return listAvailableEntitlementPools(null, o, null, activeOn, true);
    }

    /**
     * Return all pools referencing the given entitlement as their source entitlement.
     *
     * @param e Entitlement
     * @return Pools created as a result of this entitlement.
     */
    public List<Pool> listBySourceEntitlement(Entitlement e) {
        List<Pool> results = createSecureCriteria()
            .add(Restrictions.eq("sourceEntitlement", e)).list();
        if (results == null) {
            results = new LinkedList<Pool>();
        }
        return results;
    }

    /**
     * Returns list of pools available to the consumer.
     *
     * @param c Consumer to filter
     * @return pools available to the consumer.
     */
    @Transactional
    public List<Pool> listByConsumer(Consumer c) {
        return listAvailableEntitlementPools(c, c.getOwner(), (String) null, null,
            true);
    }

    /**
     * List all entitlement pools for the given owner and product.
     *
     * @param owner owner of the entitlement pool
     * @param productId product filter.
     * @return list of EntitlementPools
     */
    @Transactional
    public List<Pool> listByOwnerAndProduct(Owner owner,
            String productId) {
        return listAvailableEntitlementPools(null, owner, productId, null, false);
    }

    @SuppressWarnings("unchecked")
    public List<Pool> listExpiredPools() {
        Date today = new Date();
        Criteria crit = createSecureCriteria().add(
            Restrictions.lt("endDate", today));
        List<Pool> results = crit.list();
        if (results == null) {
            results = new LinkedList<Pool>();
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public List<Pool> listAvailableEntitlementPools(Consumer c, Owner o, String productId,
        Date activeOn, boolean activeOnly) {

        return listAvailableEntitlementPools(c, o, productId, activeOn, activeOnly,
            new PoolFilterBuilder(), null, false).getPageData();
    }

    @Transactional
    public List<Pool> listByFilter(PoolFilterBuilder filters) {
        return listAvailableEntitlementPools(
                null, null, null, null, false, filters, null, false).getPageData();
    }

    /**
     * List entitlement pools.
     *
     * Pools will be refreshed from the underlying subscription service.
     *
     * @param c Consumer being entitled.
     * @param o Owner whose subscriptions should be inspected.
     * @param productId only entitlements which provide this product are included.
     * @param activeOn Indicates to return only pools valid on this date.
     *        Set to null for no date filtering.
     * @param activeOnly if true, only active entitlements are included.
     * @param filters filter builder with set filters to apply to the criteria.
     * @param pageRequest used to specify paging criteria.
     * @param postFilter if you plan on filtering the list in java
     * @return List of entitlement pools.
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public Page<List<Pool>> listAvailableEntitlementPools(Consumer c, Owner o, String productId,
        Date activeOn, boolean activeOnly, PoolFilterBuilder filters, PageRequest pageRequest,
        boolean postFilter) {

        if (o == null && c != null) {
            o = c.getOwner();
        }

        if (log.isDebugEnabled()) {
            log.debug("Listing available pools for:");
            log.debug("   consumer: " + c);
            log.debug("   owner: " + o);
            log.debug("   product: " + productId);
        }

        Criteria crit = createSecureCriteria()
            .createAlias("product", "product")
            .createAlias("providedProducts", "provProd", CriteriaSpecification.LEFT_JOIN)
            .createAlias("provProd.productContent", "ppcw", CriteriaSpecification.LEFT_JOIN)
            .createAlias("ppcw.content", "ppContent", CriteriaSpecification.LEFT_JOIN)
            .setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);

        if (activeOnly) {
            crit.add(Restrictions.eq("activeSubscription", Boolean.TRUE));
        }
        if (c != null) {
            crit.add(Restrictions.eq("owner", c.getOwner()));

            // Ask the rules for any business logic criteria to filter with for
            // this consumer
            List<Criterion> filterCriteria = poolCriteria.availableEntitlementCriteria(c);

            if (log.isDebugEnabled()) {
                log.debug("Got " + filterCriteria.size() + " query filters from database.");
            }

            for (Criterion rulesCriteria : filterCriteria) {
                crit.add(rulesCriteria);
            }
        }
        if (o != null) {
            crit.add(Restrictions.eq("owner", o));
            crit.add(Restrictions.ne("product.name", Product.ueberProductNameForOwner(o)));
        }
        if (activeOn != null) {
            crit.add(Restrictions.le("startDate", activeOn));
            crit.add(Restrictions.ge("endDate", activeOn));
        }

        if (productId != null) {
            crit.add(Restrictions.or(
                Restrictions.eq("product.id", productId),
                Restrictions.eq("provProd.id", productId)
            ));
        }

        // Append any specified filters
        if (filters != null) {
            filters.applyTo(crit);
        }

        return listByCriteria(crit, pageRequest, postFilter);
    }

    @Transactional
    public List<Pool> listPoolsRestrictedToUser(String username) {
        return listByCriteria(
            currentSession().createCriteria(Pool.class)
                .add(Restrictions.eq("restrictedToUsername", username)));
    }

    @Transactional
    public Pool findUeberPool(Owner o) {
        return (Pool) createSecureCriteria()
            .createAlias("product", "product")
            .add(Restrictions.eq("owner", o))
            .add(Restrictions.eq("product.name", Product.ueberProductNameForOwner(o)))
            .uniqueResult();
    }

    @SuppressWarnings("unchecked")
    public List<Entitlement> retrieveFreeEntitlementsOfPool(Pool existingPool,
        boolean lifo) {
        return criteriaToSelectEntitlementForPool(existingPool)
            .addOrder(lifo ? Order.desc("created") : Order.asc("created"))
            .list();
    }

    @SuppressWarnings("unchecked")
    public List<String> retrieveFreeEntitlementIdsOfPool(Pool existingPool,
        boolean lifo) {
        return criteriaToSelectEntitlementForPool(existingPool)
            .addOrder(lifo ? Order.desc("created") : Order.asc("created"))
            .setProjection(Projections.id())
            .list();
    }

    public void deactivatePool(Pool pool) {
        if (log.isDebugEnabled()) {
            log.info("Subscription disappeared for pool: " + pool);
        }
        pool.setActiveSubscription(Boolean.FALSE);
        merge(pool);
    }

    private Criteria criteriaToSelectEntitlementForPool(Pool entitlementPool) {
        return this.currentSession().createCriteria(Entitlement.class)
            .add(Restrictions.eq("pool", entitlementPool));
    }

    /**
     * @param entitlementPool entitlement pool to search.
     * @return entitlements in the given pool.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public List<Entitlement> entitlementsIn(Pool entitlementPool) {
        return criteriaToSelectEntitlementForPool(entitlementPool).list();
    }

    /**
     * @param subId Subscription to look up pools by
     * @return pools from the given subscription, sorted by pool.id to avoid deadlocks
     */
    @SuppressWarnings("unchecked")
    public List<Pool> lookupBySubscriptionId(String subId) {
        return createSecureCriteria()
            .createAlias("sourceSubscription", "sourceSub")
            .add(Restrictions.eq("sourceSub.subscriptionId", subId))
            .addOrder(Order.asc("id")).list();
    }

    /**
     * Attempts to find pools which are over subscribed after the creation or modification
     * of the given entitlement.
     *
     * To do this we search for only the pools related to the subscription ID which
     * could have changed, the two cases where this can happen are:
     *
     * 1. Bonus pool (not derived from any entitlement) after a bind. (in cases such as
     * exporting to downstream)
     * 2. A derived pool whose source entitlement just had it's quantity reduced.
     *
     * This has to be done carefully to avoid potential performance problems with
     * virt_bonus on-site subscriptions where one pool is created per physical
     * entitlement.
     *
     * @param subId Subscription ID of the pool.
     * @param ent Entitlement just created or modified.
     * @return Pools with too many entitlements for their new quantity.
     */
    @SuppressWarnings("unchecked")
    public List<Pool> lookupOversubscribedBySubscriptionId(String subId, Entitlement ent) {
        return currentSession().createCriteria(Pool.class)
            .createAlias("sourceSubscription", "sourceSub")
            .add(Restrictions.eq("sourceSub.subscriptionId", subId))
            .add(Restrictions.ge("quantity", 0L))
            .add(Restrictions.gtProperty("consumed", "quantity"))
            .add(Restrictions.or(
                Restrictions.isNull("sourceEntitlement"),
                Restrictions.eqOrIsNull("sourceEntitlement", ent)))
            .list();
    }

    @Transactional
    public Pool replicate(Pool pool) {
        for (PoolAttribute pa : pool.getAttributes()) {
            pa.setPool(pool);
        }

        pool.setSourceEntitlement(null);

        this.currentSession().replicate(pool, ReplicationMode.EXCEPTION);

        return pool;
    }

    @Transactional
    public Pool create(Pool entity) {

        /* Ensure all referenced PoolAttributes are correctly pointing to
         * this pool. This is useful for pools being created from
         * incoming json.
         */
        for (PoolAttribute attr : entity.getAttributes()) {
            attr.setPool(entity);
        }

        return super.create(entity);
    }

    private static final String CONSUMER_FILTER = "Entitlement_CONSUMER_FILTER";

    public int getNoOfDependentEntitlements(String entitlementId) {
        Filter consumerFilter = disableConsumerFilter();
        Integer result = (Integer)
            currentSession().createCriteria(Entitlement.class)
                .setProjection(Projections.rowCount())
                .createCriteria("pool")
                    .createCriteria("sourceEntitlement")
                        .add(Restrictions.idEq(entitlementId))
                .list().get(0);
        enableIfPrevEnabled(consumerFilter);
        return result;
    }

    // TODO: watch out for performance. Should we limit the certificates
    // retrieved?
    @SuppressWarnings("unchecked")
    public List<EntitlementCertificate> retrieveEntCertsOfPoolsWithSourceEntitlement(
        String entId) {
        return
            currentSession().createCriteria(EntitlementCertificate.class)
            .createCriteria("entitlement")
                .createCriteria("pool")
                    .createCriteria("sourceEntitlement").add(Restrictions.idEq(entId))
            .list();
    }


    /**
     * @param session
     * @param consumerFilter
     */
    private void enableIfPrevEnabled(Filter consumerFilter) {
        // if filter was previously enabled, restore it.
        if (consumerFilter != null) {
            FilterImpl filterImpl = (FilterImpl) consumerFilter;
            Filter filter = currentSession().enableFilter(CONSUMER_FILTER);
            filter.setParameter("consumer_id", filterImpl.getParameter("consumer_id"));
        }
    }

    public Filter disableConsumerFilter() {
        Filter consumerFilter = currentSession().getEnabledFilter(CONSUMER_FILTER);
        currentSession().disableFilter(CONSUMER_FILTER);
        return consumerFilter;
    }

    public Pool lockAndLoad(Pool pool) {
        currentSession().refresh(pool, LockOptions.UPGRADE);
        getEntityManager().refresh(pool);
        return pool;
    }

    public List<ActivationKey> getActivationKeysForPool(Pool p) {
        List<ActivationKey> activationKeys = new ArrayList<ActivationKey>();
        List<ActivationKeyPool> activationKeyPools = currentSession().createCriteria(
            ActivationKeyPool.class).add(Restrictions.eq("pool", p)).list();

        for (ActivationKeyPool akp : activationKeyPools) {
            activationKeys.add(akp.getKey());
        }

        return activationKeys;
    }

    /**
     * Method to compile service/support level lists. One is the available levels for
     *  consumers for this owner. The second is the level names that are exempt. Exempt
     *  means that a product pool with this level can be used with a consumer of any
     *  service level.
     *
     * @param owner The owner that has the list of available service levels for
     *              its consumers
     * @param exempt boolean to show if the desired list is the levels that are
     *               explicitly marked with the support_level_exempt attribute.
     * @return Set of levels based on exempt flag.
     */
    public Set<String> retrieveServiceLevelsForOwner(Owner owner, boolean exempt) {
        String stmt = "SELECT DISTINCT Attribute.name, Attribute.value, Product.id " +
            "FROM Pool AS Pool " +
            "  INNER JOIN Pool.product AS Product " +
            "  INNER JOIN Product.attributes AS Attribute " +
            "WHERE Pool.owner.id = :owner_id " +
            "  AND (Attribute.name = 'support_level' OR Attribute.name = 'support_level_exempt') " +
            "  ORDER BY Attribute.name DESC";

        Query q = currentSession().createQuery(stmt);
        q.setParameter("owner_id", owner.getId());
        List<Object[]> results = q.list();

        // Use case insensitive comparison here, since we treat
        // Premium the same as PREMIUM or premium, to make it easier for users to specify
        // a level on the CLI. However, use the original case, since Premium is more
        // attractive than PREMIUM.
        Set<String> slaSet = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        Set<String> exemptSlaSet = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);

        Set<String> exemptProductIds = new HashSet<String>();

        for (Object[] result : results) {
            String name = (String) result[0];
            String value = (String) result[1];
            String productId = (String) result[2];

            if ("support_level_exempt".equals(name) && "true".equalsIgnoreCase(value)) {
                exemptProductIds.add(productId);
            }
            else if ("support_level".equalsIgnoreCase(name) &&
                (value != null && !value.trim().equals("")) &&
                exemptProductIds.contains(productId)) {

                exemptSlaSet.add(value);
            }
        }

        for (Object[] result : results) {
            String name = (String) result[0];
            String value = (String) result[1];

            if (!"support_level_exempt".equals(name) && !exemptSlaSet.contains(value)) {
                slaSet.add(value);
            }
        }

        if (exempt) {
            return exemptSlaSet;
        }
        return slaSet;
    }

    /**
     * There is an issue with attributes in the database. Duplicate attribute
     *  name/value pairs were showing up from outside the Candlepin code.
     *  Only one was getting pulled into the HashSet and the other ignored.
     *  Uses explicit lookup and deletion instead of the Hibernate cascade which
     *  relies on the data structure in memory.
     *
     * @param entity pool to be deleted.
     */
    @Transactional
    public void delete(Pool entity) {
        Pool toDelete = find(entity.getId());
        if (toDelete != null) {
            log.debug("DELETING POOL w/SUBSCRIPTION ID: {}", toDelete.getSubscriptionId());
            currentSession().delete(toDelete);
            this.flush();
        }
        else {
            log.info("Pool " + entity.getId() + " not found. Skipping deletion. noop");
        }
    }

    /**
     * @param consumer
     * @param stackId
     * @return Number of derived pools which exist for the given consumer and stack
     */
    public Pool getSubPoolForStackId(Consumer consumer, String stackId) {
        Criteria getCount = createSecureCriteria()
            .createAlias("sourceStack", "ss")
            .add(Restrictions.eq("ss.sourceConsumer", consumer))
            .add(Restrictions.and(Restrictions.isNotNull("ss.sourceStackId"),
                                  Restrictions.eq("ss.sourceStackId", stackId)));
        return (Pool) getCount.uniqueResult();
    }

    @SuppressWarnings("unchecked")
    public List<Pool> getOwnerSubPoolsForStackId(Owner owner, String stackId) {
        return createSecureCriteria()
            .createAlias("sourceStack", "ss")
            .add(Restrictions.eq("ss.sourceStackId", stackId))
            .add(Restrictions.eq("owner", owner))
            .list();
    }

    /**
     * Lookup all pools for subscriptions which are not in the given list of subscription
     * IDs. Used for pool cleanup during refresh.
     *
     * @param owner
     * @param expectedSubIds Full list of all expected subscription IDs.
     * @return a list of pools for subscriptions not matching the specified subscription list
     */
    @SuppressWarnings("unchecked")
    public List<Pool> getPoolsFromBadSubs(Owner owner, Collection<String> expectedSubIds) {
        Criteria crit = currentSession().createCriteria(Pool.class)
                .add(Restrictions.eq("owner", owner));

        if (!expectedSubIds.isEmpty()) {
            crit.createAlias("sourceSubscription", "sourceSub");
            crit.add(Restrictions.and(
                Restrictions.not(Restrictions.in("sourceSub.subscriptionId", expectedSubIds)),
                Restrictions.isNotNull("sourceSub.subscriptionId")
            ));
        }

        crit.addOrder(Order.asc("id"));
        return crit.list();
    }

    @SuppressWarnings("unchecked")
    public List<Pool> getPoolsBySubscriptionId(String subId) {
        return currentSession().createCriteria(Pool.class)
            .createAlias("sourceSubscription", "sourceSub",
                    JoinType.LEFT_OUTER_JOIN)
            .add(Restrictions.eq("sourceSub.subscriptionId", subId))
            .addOrder(Order.asc("id"))
            .list();
    }

    @SuppressWarnings("unchecked")
    public List<Pool> getPoolsBySubscriptionIds(Collection<String> subIds) {
        return currentSession().createCriteria(Pool.class)
            .createAlias("sourceSubscription", "sourceSub", JoinType.LEFT_OUTER_JOIN)
            .add(Restrictions.in("sourceSub.subscriptionId", subIds))
            .addOrder(Order.asc("id"))
            .list();
    }

    @SuppressWarnings("unchecked")
    public Pool getMasterPoolBySubscriptionId(String subscriptionId) {
        return (Pool) currentSession().createCriteria(Pool.class)
            .createAlias("sourceSubscription", "srcsub", JoinType.LEFT_OUTER_JOIN)
            .add(Restrictions.eq("srcsub.subscriptionId", subscriptionId))
            .add(Restrictions.eq("srcsub.subscriptionSubKey", "master"))
            .uniqueResult();
    }

    @SuppressWarnings("unchecked")
    public List<Pool> listMasterPools() {
        return this.currentSession().createCriteria(Pool.class)
            .createAlias("sourceSubscription", "srcsub", JoinType.LEFT_OUTER_JOIN)
            .add(Restrictions.eq("srcsub.subscriptionSubKey", "master"))
            .list();
    }

    @SuppressWarnings("unchecked")
    public List<Pool> getOwnersFloatingPools(Owner owner) {
        return currentSession().createCriteria(Pool.class)
                .add(Restrictions.eq("owner", owner))
                .createAlias("sourceSubscription", "sourceSub",
                    JoinType.LEFT_OUTER_JOIN)
                .add(Restrictions.isNull("sourceSub.subscriptionId"))
                .addOrder(Order.asc("id"))
                .list();
    }

    /**
     * Retrieves the set of all known product IDs, as determined by looking only at pool data. If
     * there are no known products, this method returns an empty set.
     *
     * @return
     *  a set of all known product IDs.
     */
    @SuppressWarnings("unchecked")
    public Set<String> getAllKnownProductIds() {
        // Impl note:
        // HQL does not (properly) support unions, so we have to do this query multiple times.
        Set<String> result = new HashSet<String>();

        Query query = this.currentSession().createQuery(
            "SELECT DISTINCT P.product.id " +
            "FROM Pool P " +
            "WHERE NULLIF(P.product.id, '') IS NOT NULL"
        );
        result.addAll(query.list());

        query = this.currentSession().createQuery(
            "SELECT DISTINCT P.derivedProduct.id " +
            "FROM Pool P " +
            "WHERE NULLIF(P.derivedProduct.id, '') IS NOT NULL"
        );
        result.addAll(query.list());

        query = this.currentSession().createQuery(
            "SELECT DISTINCT PP.id " +
            "FROM Pool P INNER JOIN P.providedProducts AS PP " +
            "WHERE NULLIF(PP.id, '') IS NOT NULL"
        );
        result.addAll(query.list());

        query = this.currentSession().createQuery(
            "SELECT DISTINCT DPP.id " +
            "FROM Pool P INNER JOIN P.derivedProvidedProducts AS DPP " +
            "WHERE NULLIF(DPP.id, '') IS NOT NULL"
        );
        result.addAll(query.list());


        // TODO: These may not be necessary if the subscriptions table is empty in Hosted. Though,
        // if they're empty, then these queries will take virtually no time to run anyway.
        query = this.currentSession().createQuery(
            "SELECT DISTINCT S.product.id " +
            "FROM Subscription S " +
            "WHERE NULLIF(S.product.id, '') IS NOT NULL"
        );
        result.addAll(query.list());

        query = this.currentSession().createQuery(
            "SELECT DISTINCT S.derivedProduct.id " +
            "FROM Subscription S " +
            "WHERE NULLIF(S.derivedProduct.id, '') IS NOT NULL"
        );
        result.addAll(query.list());

        query = this.currentSession().createQuery(
            "SELECT DISTINCT PP.id " +
            "FROM Subscription S INNER JOIN S.providedProducts AS PP " +
            "WHERE NULLIF(PP.id, '') IS NOT NULL"
        );
        result.addAll(query.list());

        query = this.currentSession().createQuery(
            "SELECT DISTINCT DPP.id " +
            "FROM Subscription S INNER JOIN S.derivedProvidedProducts AS DPP " +
            "WHERE NULLIF(DPP.id, '') IS NOT NULL"
        );
        result.addAll(query.list());

        // Return!
        return result;
    }

    /**
     * Retrieves the set of all known product IDs for the specified owner, as determined by looking
     * only at pool data. If there are no known products for the given owner, this method returns an
     * empty set.
     *
     * @param owner
     *  The owner for which to retrieve all known product IDs
     *
     * @return
     *  a set of all known product IDs for the specified owner
     */
    @SuppressWarnings("unchecked")
    public Set<String> getAllKnownProductIdsForOwner(Owner owner) {
        // Impl note:
        // HQL does not (properly) support unions, so we have to do this query multiple times.
        Set<String> result = new HashSet<String>();

        Query query = this.currentSession().createQuery(
            "SELECT DISTINCT P.product.id " +
            "FROM Pool P " +
            "WHERE NULLIF(P.product.id, '') IS NOT NULL " +
            "AND P.owner = :owner"
        );
        query.setParameter("owner", owner);
        result.addAll(query.list());

        query = this.currentSession().createQuery(
            "SELECT DISTINCT P.derivedProduct.id " +
            "FROM Pool P " +
            "WHERE NULLIF(P.derivedProduct.id, '') IS NOT NULL " +
            "AND P.owner = :owner"
        );
        query.setParameter("owner", owner);
        result.addAll(query.list());

        query = this.currentSession().createQuery(
            "SELECT DISTINCT PP.id " +
            "FROM Pool P INNER JOIN P.providedProducts AS PP " +
            "WHERE NULLIF(PP.id, '') IS NOT NULL " +
            "AND P.owner = :owner"
        );
        query.setParameter("owner", owner);
        result.addAll(query.list());

        query = this.currentSession().createQuery(
            "SELECT DISTINCT DPP.id " +
            "FROM Pool P INNER JOIN P.derivedProvidedProducts AS DPP " +
            "WHERE NULLIF(DPP.id, '') IS NOT NULL " +
            "AND P.owner = :owner"
        );
        query.setParameter("owner", owner);
        result.addAll(query.list());


        // TODO: These may not be necessary if the subscriptions table is empty in Hosted. Though,
        // if they're empty, then these queries will take virtually no time to run anyway.
        query = this.currentSession().createQuery(
            "SELECT DISTINCT S.product.id " +
            "FROM Subscription S " +
            "WHERE NULLIF(S.product.id, '') IS NOT NULL " +
            "AND S.owner = :owner"
        );
        query.setParameter("owner", owner);
        result.addAll(query.list());

        query = this.currentSession().createQuery(
            "SELECT DISTINCT S.derivedProduct.id " +
            "FROM Subscription S " +
            "WHERE NULLIF(S.derivedProduct.id, '') IS NOT NULL " +
            "AND S.owner = :owner"
        );
        query.setParameter("owner", owner);
        result.addAll(query.list());

        query = this.currentSession().createQuery(
            "SELECT DISTINCT PP.id " +
            "FROM Subscription S INNER JOIN S.providedProducts AS PP " +
            "WHERE NULLIF(PP.id, '') IS NOT NULL " +
            "AND S.owner = :owner"
        );
        query.setParameter("owner", owner);
        result.addAll(query.list());

        query = this.currentSession().createQuery(
            "SELECT DISTINCT DPP.id " +
            "FROM Subscription S INNER JOIN S.derivedProvidedProducts AS DPP " +
            "WHERE NULLIF(DPP.id, '') IS NOT NULL " +
            "AND S.owner = :owner"
        );
        query.setParameter("owner", owner);
        result.addAll(query.list());

        // Return!
        return result;
    }
}
