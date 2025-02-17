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
package org.candlepin.controller;

import org.candlepin.audit.Event;
import org.candlepin.audit.Event.Target;
import org.candlepin.audit.Event.Type;
import org.candlepin.audit.EventBuilder;
import org.candlepin.audit.EventFactory;
import org.candlepin.audit.EventSink;
import org.candlepin.common.config.Configuration;
import org.candlepin.common.paging.Page;
import org.candlepin.common.paging.PageRequest;
import org.candlepin.config.ConfigProperties;
import org.candlepin.model.Consumer;
import org.candlepin.model.ConsumerCurator;
import org.candlepin.model.ConsumerInstalledProduct;
import org.candlepin.model.Content;
import org.candlepin.model.ContentCurator;
import org.candlepin.model.Entitlement;
import org.candlepin.model.EntitlementCertificate;
import org.candlepin.model.EntitlementCertificateCurator;
import org.candlepin.model.EntitlementCurator;
import org.candlepin.model.Environment;
import org.candlepin.model.Owner;
import org.candlepin.model.Pool;
import org.candlepin.model.Pool.PoolType;
import org.candlepin.model.PoolCurator;
import org.candlepin.model.PoolFilterBuilder;
import org.candlepin.model.PoolQuantity;
import org.candlepin.model.Product;
import org.candlepin.model.ProductContent;
import org.candlepin.model.ProductCurator;
import org.candlepin.model.Subscription;
import org.candlepin.model.activationkeys.ActivationKey;
import org.candlepin.policy.EntitlementRefusedException;
import org.candlepin.policy.ValidationResult;
import org.candlepin.policy.js.activationkey.ActivationKeyRules;
import org.candlepin.policy.js.autobind.AutobindRules;
import org.candlepin.policy.js.compliance.ComplianceRules;
import org.candlepin.policy.js.compliance.ComplianceStatus;
import org.candlepin.policy.js.entitlement.Enforcer;
import org.candlepin.policy.js.entitlement.Enforcer.CallerType;
import org.candlepin.policy.js.pool.PoolHelper;
import org.candlepin.policy.js.pool.PoolRules;
import org.candlepin.policy.js.pool.PoolUpdate;
import org.candlepin.resource.dto.AutobindData;
import org.candlepin.service.EntitlementCertServiceAdapter;
import org.candlepin.service.SubscriptionServiceAdapter;
import org.candlepin.sync.SubscriptionReconciler;
import org.candlepin.util.CertificateSizeException;
import org.candlepin.util.Util;
import org.candlepin.version.CertVersionConflictException;

import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PoolManager
 */
public class CandlepinPoolManager implements PoolManager {

    private PoolCurator poolCurator;
    private static Logger log = LoggerFactory.getLogger(CandlepinPoolManager.class);

    private EventSink sink;
    private EventFactory eventFactory;
    private Configuration config;
    private Enforcer enforcer;
    private PoolRules poolRules;
    private EntitlementCurator entitlementCurator;
    private ConsumerCurator consumerCurator;
    private EntitlementCertServiceAdapter entCertAdapter;
    private EntitlementCertificateCurator entitlementCertificateCurator;
    private ComplianceRules complianceRules;
    private ProductCurator productCurator;
    private AutobindRules autobindRules;
    private ActivationKeyRules activationKeyRules;
    private ProductCurator prodCurator;
    private ContentCurator contentCurator;

    /**
     * @param poolCurator
     * @param subAdapter
     * @param sink
     * @param eventFactory
     * @param config
     */
    @Inject
    public CandlepinPoolManager(PoolCurator poolCurator,
        ProductCurator productCurator,
        EntitlementCertServiceAdapter entCertAdapter, EventSink sink,
        EventFactory eventFactory, Configuration config, Enforcer enforcer,
        PoolRules poolRules, EntitlementCurator curator1, ConsumerCurator consumerCurator,
        EntitlementCertificateCurator ecC, ComplianceRules complianceRules,
        AutobindRules autobindRules, ActivationKeyRules activationKeyRules,
        ProductCurator prodCurator, ContentCurator contentCurator) {

        this.poolCurator = poolCurator;
        this.sink = sink;
        this.eventFactory = eventFactory;
        this.config = config;
        this.entitlementCurator = curator1;
        this.consumerCurator = consumerCurator;
        this.enforcer = enforcer;
        this.poolRules = poolRules;
        this.entCertAdapter = entCertAdapter;
        this.entitlementCertificateCurator = ecC;
        this.complianceRules = complianceRules;
        this.productCurator = productCurator;
        this.autobindRules = autobindRules;
        this.activationKeyRules = activationKeyRules;
        this.prodCurator = prodCurator;
        this.contentCurator = contentCurator;
    }

    /*
     * We need to update/regen entitlements in the same transaction we update pools
     * so we don't miss anything
     */
    void refreshPoolsWithRegeneration(SubscriptionServiceAdapter subAdapter, Owner owner, boolean lazy) {
        log.info("Refreshing pools for owner: {}", owner);
        List<Subscription> subs = subAdapter.getSubscriptions(owner);

        log.debug("Found {} existing subscriptions.", subs.size());

        SubscriptionReconciler reconciler = new SubscriptionReconciler();
        reconciler.reconcile(owner, subs, poolCurator);

        Set<String> subIds = Util.newSet();

        // TODO:
        // Does changedContent have a use? Should refreshing products imply refreshing content?
        Set<Content> changedContent = refreshContent(owner, subs);
        Set<Product> changedProducts = refreshProducts(owner, subs);

        List<String> deletedSubs = new LinkedList<String>();
        for (Subscription sub : subs) {
            String subId = sub.getId();
            subIds.add(subId);

            log.debug("Processing subscription: {}", sub);

            // Remove expired subscriptions
            if (isExpired(sub)) {
                deletedSubs.add(subId);
                log.info("Skipping expired subscription: {}", sub);
                continue;
            }

            refreshPoolsForSubscription(sub, lazy, changedProducts);
        }

        // We deleted some, need to take that into account so we
        // remove everything that isn't actually active
        subIds.removeAll(deletedSubs);
        // delete pools whose subscription disappeared:
        for (Pool pool : poolCurator.getPoolsFromBadSubs(owner, subIds)) {
            if (pool.getSourceSubscription() != null && !pool.getType().isDerivedType()) {
                deletePool(pool);
            }
        }

        // TODO: break this call into smaller pieces.  There may be lots of floating pools
        List<Pool> floatingPools = poolCurator.getOwnersFloatingPools(owner);
        updateFloatingPools(floatingPools, lazy, changedProducts);
    }

    /**
     * Refreshes the specified content under the given owner/org.
     *
     * @param owner
     *  The owner for which to refresh content
     *
     * @param subs
     *  The subscriptions from which to pull content
     *
     * @return
     *  the set of existing content which was updated/changed as a result of this operation
     */
    protected Set<Content> refreshContent(Owner owner, Collection<Subscription> subs) {
        // All inbound content, mapped by content ID. We're assuming it's all under the same org
        Map<String, Content> content = new HashMap<String, Content>();

        log.info("Refreshing content for {} subscriptions.", subs.size());

        for (Subscription sub : subs) {
            // Grab all the content from each product
            this.addProductContentToMap(content, sub.getProduct());
            this.addProductContentToMap(content, sub.getDerivedProduct());

            for (Product product : sub.getProvidedProducts()) {
                this.addProductContentToMap(content, product);
            }

            for (Product product : sub.getDerivedProvidedProducts()) {
                this.addProductContentToMap(content, product);
            }
        }

        Set<Content> changed = this.getChangedContent(owner, content.values());

        // Go back through each sub and update content references so we don't end up with dangling,
        // transient or duplicate references on any of the subs' products.
        for (Subscription sub : subs) {
            this.updateContentRefs(sub.getProduct());
            this.updateContentRefs(sub.getDerivedProduct());

            for (Product product : sub.getProvidedProducts()) {
                this.updateContentRefs(product);
            }

            for (Product product : sub.getDerivedProvidedProducts()) {
                this.updateContentRefs(product);
            }
        }

        return changed;
    }

    private void addProductContentToMap(Map<String, Content> contentMap, Product product) {
        if (product == null) {
            return;
        }

        for (ProductContent pc : product.getProductContent()) {
            Content content = pc.getContent();

            // Check that the content hasn't changed if we've already seen it.
            Content existing = contentMap.get(content.getId());

            if (existing != null && !content.equals(existing)) {
                // TODO: Is this condition exception worthy?
                log.warn(
                    "Preexisting content found with new data. Using new version for content: {}",
                    content.getId()
                );
            }

            contentMap.put(content.getId(), content);
        }
    }

    private void updateContentRefs(Product product) {
        if (product == null) {
            return;
        }

        for (ProductContent pc : product.getProductContent()) {
            Content content = pc.getContent();
            Content existing = this.contentCurator.lookupById(content.getOwner(), content.getId());

            if (existing == null) {
                // This should never happen.
                throw new RuntimeException("Unable to resolve content reference");
            }

            pc.setContent(existing);
        }
    }

    public Set<Content> getChangedContent(Owner owner, Collection<Content> content) {
        Set<Content> changed = Util.newSet();

        log.debug("Syncing {} incoming content.", content.size());

        for (Content inbound : content) {
            Content existing = this.contentCurator.lookupById(owner, inbound.getId());

            if (existing == null) {
                log.info("Creating new content for org {}: {}", owner.getKey(), inbound.getId());
                inbound.setOwner(owner);

                this.contentCurator.create(inbound);
            }
            else if (!inbound.equals(existing)) {
                log.info("Updating existing content for org {}: {}", owner.getKey(), inbound.getId());
                inbound.setOwner(owner);

                this.contentCurator.createOrUpdate(inbound);
                changed.add(inbound);
            }
        }

        return changed;
    }

    Set<Product> refreshProducts(Owner o, List<Subscription> subs) {
        /*
         * Build a master list of all products on the incoming subscriptions. Note that
         * these product objects are detached, and need to be synced with what's in the
         * database.
         */
        Map<String, Product> products = new HashMap<String, Product>();

        log.info("Refreshing products for {} subscriptions.", subs.size());

        for (Subscription sub : subs) {
            this.addProductToMap(products, sub.getProduct());
            this.addProductToMap(products, sub.getDerivedProduct());

            for (Product product : sub.getProvidedProducts()) {
                this.addProductToMap(products, product);
            }

            for (Product product : sub.getDerivedProvidedProducts()) {
                this.addProductToMap(products, product);
            }
        }

        Set<Product> changed = this.getChangedProducts(o, products.values());

        // Go back through each sub and update product references so we don't end up with dangling,
        // transient or duplicate references on any of the subs.
        for (Subscription sub : subs) {
            sub.setProduct(this.resolveProductRef(sub.getProduct()));

            if (sub.getDerivedProduct() != null) {
                sub.setDerivedProduct(this.resolveProductRef(sub.getDerivedProduct()));
            }

            Set<Product> pset = new HashSet<Product>();
            for (Product product : sub.getProvidedProducts()) {
                pset.add(this.resolveProductRef(product));
            }
            sub.setProvidedProducts(pset);

            pset.clear();
            for (Product product : sub.getDerivedProvidedProducts()) {
                pset.add(this.resolveProductRef(product));
            }
            sub.setDerivedProvidedProducts(pset);
        }

        return changed;
    }

    private void addProductToMap(Map<String, Product> productMap, Product product) {
        if (product != null) {
            // Check that the product hasn't changed if we've already seen it.
            Product existing = productMap.get(product.getId());

            if (existing != null && !this.hasProductChanged(existing, product)) {
                // TODO: Is this condition exception worthy?
                log.warn(
                    "Preexisting product found with new data. Using new version for product: {}",
                    product.getId()
                );
            }

            productMap.put(product.getId(), product);
        }
    }

    private Product resolveProductRef(Product product) {
        Product resolved = null;

        if (product != null && product.getOwner() != null && product.getId() != null) {
            resolved = this.prodCurator.lookupById(product.getOwner(), product.getId());
        }

        return resolved != null ? resolved : product;
    }

    public Set<Product> getChangedProducts(Owner o, Collection<Product> allProducts) {
        Set<Product> changedProducts = Util.newSet();

        HashMap<String, Content> cmap = new HashMap<String, Content>();

        log.debug("Syncing {} incoming products.", allProducts.size());
        for (Product incoming : allProducts) {
            Product existing = prodCurator.lookupById(o, incoming.getId());

            if (existing == null) {
                log.info("Creating new product for org {}: {}", o.getKey(), incoming.getId());
                incoming.setOwner(o);
                prodCurator.create(incoming);
            }
            else {
                if (hasProductChanged(existing, incoming)) {
                    log.info("Product changed for org {}: {}", o.getKey(), incoming.getId());
                    prodCurator.createOrUpdate(incoming);
                    changedProducts.add(incoming);
                    // TODO: signal back to caller the set of changed products, we'll
                    // need to know during refreshing of existing pools.
                }
            }
        }

        return changedProducts;
    }

    // TODO: move to comparator? Perhaps updating Product.equals and using that would be better?
    protected final boolean hasProductChanged(Product existingProd, Product importedProd) {
        // trying to go in order from least to most work.
        if (!existingProd.getName().equals(importedProd.getName())) {
            return true;
        }

        if (!existingProd.getMultiplier().equals(importedProd.getMultiplier())) {
            return true;
        }

        if (existingProd.getAttributes().size() != importedProd.getAttributes().size()) {
            return true;
        }
        if (Sets.intersection(existingProd.getAttributes(),
            importedProd.getAttributes()).size() != existingProd.getAttributes().size()) {
            return true;
        }

        if (existingProd.getProductContent().size() != importedProd.getProductContent().size()) {
            return true;
        }
        if (Sets.intersection(new HashSet<ProductContent>(existingProd.getProductContent()),
                new HashSet<ProductContent>(importedProd.getProductContent())).size() !=
                existingProd.getProductContent().size()) {
            return true;
        }

        return false;
    }

    @Transactional
    void refreshPoolsForSubscription(Subscription sub, boolean lazy, Set<Product> changedProducts) {
        // These don't all necessarily belong to this owner
        List<Pool> subscriptionPools = poolCurator.getPoolsBySubscriptionId(sub.getId());
        log.debug("Found {} pools for subscription {}", subscriptionPools.size(),
                sub.getId());
        if (log.isDebugEnabled()) {
            for (Pool p : subscriptionPools) {
                log.debug("    owner={} - {}", p.getOwner().getKey(), p);
            }
        }

        // Cleans up pools on other owners who have migrated subs away
        removeAndDeletePoolsOnOtherOwners(subscriptionPools, sub);

        // BUG 1012386 This will regenerate master/derived for bonus scenarios
        //  if only one of the pair still exists.
        createPoolsForSubscription(sub, subscriptionPools);

        // don't update floating here, we'll do that later
        // so we don't update anything twice
        regenerateCertificatesByEntIds(
            updatePoolsForSubscription(subscriptionPools, sub, false, changedProducts), lazy
        );
    }

    public void cleanupExpiredPools() {
        List<Pool> pools = poolCurator.listExpiredPools();
        log.info("Expired pools: {}", pools.size());
        for (Pool p : pools) {
            if (p.hasAttribute("derived_pool")) {
                // Derived pools will be cleaned up when their parent entitlement
                // is revoked.
                continue;
            }
            log.info("Cleaning up expired pool: {} ({})", p.getId(), p.getEndDate());

            deletePool(p);
        }
    }

    private boolean isExpired(Subscription subscription) {
        Date now = new Date();
        return now.after(subscription.getEndDate());
    }

    @Transactional
    private void deleteExcessEntitlements(Pool existingPool) {
        boolean lifo = !config
            .getBoolean(ConfigProperties.REVOKE_ENTITLEMENT_IN_FIFO_ORDER);

        if (existingPool.isOverflowing()) {
            // if pool quantity has reduced, then start with revocation.
            Iterator<Entitlement> iter = this.poolCurator
                .retrieveFreeEntitlementsOfPool(existingPool, lifo).iterator();

            long consumed = existingPool.getConsumed();
            long existing = existingPool.getQuantity();
            while (consumed > existing && iter.hasNext()) {
                Entitlement e = iter.next();
                revokeEntitlement(e);
                consumed -= e.getQuantity();
            }
        }
    }

    void removeAndDeletePoolsOnOtherOwners(List<Pool> existingPools, Subscription sub) {
        List<Pool> toRemove = new LinkedList<Pool>();
        for (Pool existing : existingPools) {
            if (!existing.getOwner().equals(sub.getOwner())) {
                toRemove.add(existing);
                log.warn("Removing {} because it exists in the wrong org", existing);
                if (existing.getType() == PoolType.NORMAL ||
                    existing.getType() == PoolType.BONUS) {
                    deletePool(existing);
                }
            }
        }
        existingPools.removeAll(toRemove);
    }

    /**
     * Update pool for subscription. - This method only checks for change in
     * quantity and dates of a subscription. Currently any quantity changes in
     * pool are not handled.
     *
     * @param existingPools the existing pools
     * @param sub the sub
     * @param updateStackDerived wheter or not to attempt to update stack derived
     * subscriptions
     */
    Set<String> updatePoolsForSubscription(List<Pool> existingPools,
            Subscription sub, boolean updateStackDerived,
            Set<Product> changedProducts) {

        /*
         * Rules need to determine which pools have changed, but the Java must
         * send out the events. Create an event for each pool that could change,
         * even if we won't use them all.
         */
        if (existingPools == null || existingPools.isEmpty()) {
            return new HashSet<String>(0);
        }

        log.debug("Updating {} pools for existing subscription: {}", existingPools.size(), sub);

        Map<String, EventBuilder> poolEvents = new HashMap<String, EventBuilder>();
        for (Pool existing : existingPools) {
            EventBuilder eventBuilder = eventFactory
                    .getEventBuilder(Target.POOL, Type.MODIFIED)
                    .setOldEntity(existing);
            poolEvents.put(existing.getId(), eventBuilder);
        }

        // Hand off to rules to determine which pools need updating:
        List<PoolUpdate> updatedPools = poolRules.updatePools(sub, existingPools, changedProducts);

        // Update subpools if necessary
        if (updateStackDerived && !updatedPools.isEmpty() &&
                sub.createsSubPools() && sub.isStacked()) {
            // Get all pools for the subscriptions owner derived from the subscriptions
            // stack id, because we cannot look it up by subscriptionId
            List<Pool> subPools = getOwnerSubPoolsForStackId(sub.getOwner(), sub.getStackId());

            for (Pool subPool : subPools) {
                PoolUpdate update = updatePoolFromStack(subPool, changedProducts);

                if (update.changed()) {
                    updatedPools.add(update);
                }
            }
        }

        Set<String> entsToRegen = processPoolUpdates(poolEvents, updatedPools);
        return entsToRegen;
    }

    private Set<String> processPoolUpdates(
        Map<String, EventBuilder> poolEvents, List<PoolUpdate> updatedPools) {
        Set<String> entitlementsToRegen = Util.newSet();
        for (PoolUpdate updatedPool : updatedPools) {

            Pool existingPool = updatedPool.getPool();
            log.info("Pool changed: {}", updatedPool.toString());

            // Delete pools the rules signal needed to be cleaned up:
            if (existingPool.isMarkedForDelete()) {
                log.warn("Deleting pool as requested by rules: {}", existingPool.getId());
                deletePool(existingPool);
                continue;
            }

            // save changes for the pool
            this.poolCurator.merge(existingPool);

            // Explicitly call flush to avoid issues with how we sync up the attributes.
            // This prevents "instance does not yet exist as a row in the database" errors
            // when we later try to lock the pool if we need to revoke entitlements:
            this.poolCurator.flush();

            // quantity has changed. delete any excess entitlements from pool
            if (updatedPool.getQuantityChanged()) {
                this.deleteExcessEntitlements(existingPool);
            }

            // dates changed. regenerate all entitlement certificates
            if (updatedPool.getDatesChanged() ||
                updatedPool.getProductsChanged() ||
                updatedPool.getBrandingChanged()) {
                List<String> entitlements = poolCurator
                    .retrieveFreeEntitlementIdsOfPool(existingPool, true);
                entitlementsToRegen.addAll(entitlements);
            }
            Event event = poolEvents.get(existingPool.getId())
                    .setNewEntity(existingPool)
                    .buildEvent();
            sink.queueEvent(event);
        }

        return entitlementsToRegen;
    }

    /**
     * Update pools which have no subscription attached, if applicable.
     *
     * @param floatingPools
     * @return
     */
    @Transactional
    void updateFloatingPools(List<Pool> floatingPools, boolean lazy, Set<Product> changedProducts) {
        /*
         * Rules need to determine which pools have changed, but the Java must
         * send out the events. Create an event for each pool that could change,
         * even if we won't use them all.
         */
        Map<String, EventBuilder> poolEvents = new HashMap<String, EventBuilder>();
        for (Pool existing : floatingPools) {
            EventBuilder eventBuilder = eventFactory.getEventBuilder(Target.POOL, Type.MODIFIED)
                    .setOldEntity(existing);
            poolEvents.put(existing.getId(), eventBuilder);
        }

        // Hand off to rules to determine which pools need updating:
        List<PoolUpdate> updatedPools = poolRules.updatePools(floatingPools, changedProducts);
        regenerateCertificatesByEntIds(processPoolUpdates(poolEvents, updatedPools), lazy);
    }

    /**
     * @param sub
     * @return the newly created Pools
     */
    @Override
    public List<Pool> createPoolsForSubscription(Subscription sub) {
        return createPoolsForSubscription(sub, new LinkedList<Pool>());
    }

    public List<Pool> createPoolsForSubscription(Subscription sub, List<Pool> existingPools) {
        List<Pool> pools = poolRules.createPools(sub, existingPools);
        log.debug("Creating {} pools for subscription: ", pools.size());
        for (Pool pool : pools) {
            createPool(pool);
        }

        return pools;
    }

    @Override
    public Pool createPool(Pool pool) {
        Pool created = poolCurator.create(pool);
        log.debug("   new pool: {}", pool);

        if (created != null) {
            sink.emitPoolCreated(created);
        }

        return created;
    }

    @Override
    public void updatePoolsForSubscription(Subscription subscription) {
        if (subscription == null) {
            throw new IllegalArgumentException("subscription is null");
        }

        // Ensure the subscription is not expired
        if (subscription.getEndDate() != null && subscription.getEndDate().before(new Date())) {
            this.deletePoolsForSubscription(subscription);
        }
        else {
            this.refreshPoolsForSubscription(subscription, true, Collections.<Product>emptySet());
        }
    }

    @Override
    public void deletePoolsForSubscription(Subscription subscription) {
        if (subscription == null) {
            throw new IllegalArgumentException("subscription is null");
        }

        if (subscription.getId() != null) {
            for (Pool pool : this.getPoolsBySubscriptionId(subscription.getId())) {
                this.deletePool(pool);
            }
        }
    }

    @Override
    public void deletePoolsForSubscriptions(Collection<String> subscriptionIds) {
        if (subscriptionIds == null) {
            throw new IllegalArgumentException("subscriptionIds is null");
        }

        if (subscriptionIds.size() > 0) {
            for (Pool pool : this.poolCurator.getPoolsBySubscriptionIds(subscriptionIds)) {
                this.deletePool(pool);
            }
        }
    }


    @Override
    public Pool find(String poolId) {
        return this.poolCurator.find(poolId);
    }

    @Override
    public List<Pool> lookupBySubscriptionId(String id) {
        return this.poolCurator.lookupBySubscriptionId(id);
    }

    public List<Pool> lookupOversubscribedBySubscriptionId(String subId, Entitlement ent) {
        return this.poolCurator.lookupOversubscribedBySubscriptionId(subId, ent);
    }

    /**
     * Request an entitlement by product. If the entitlement cannot be granted,
     * null will be returned. TODO: Throw exception if entitlement not granted.
     * Report why.
     *
     * @param data Autobind data containing consumer, date, etc..
     * @return Entitlement
     * @throws EntitlementRefusedException if entitlement is refused
     */
    //
    // NOTE: after calling this method both entitlement pool and consumer
    // parameters
    // will most certainly be stale. beware!
    //
    @Override
    @Transactional
    public List<Entitlement> entitleByProducts(AutobindData data)
        throws EntitlementRefusedException {
        Consumer consumer = data.getConsumer();
        String[] productIds = data.getProductIds();
        Collection<String> fromPools = data.getPossiblePools();
        Date entitleDate = data.getOnDate();
        Owner owner = consumer.getOwner();

        List<Entitlement> entitlements = new LinkedList<Entitlement>();

        List<PoolQuantity> bestPools = getBestPools(consumer, productIds,
            entitleDate, owner, null, fromPools);

        if (bestPools == null) {
            List<String> fullList = new ArrayList<String>();
            fullList.addAll(Arrays.asList(productIds));
            for (ConsumerInstalledProduct cip : consumer.getInstalledProducts()) {
                fullList.add(cip.getId());
            }
            log.info("No entitlements available for products: {}", fullList);
            return null;
        }

        // now make the entitlements
        for (PoolQuantity entry : bestPools) {
            entitlements.add(addOrUpdateEntitlement(consumer, entry.getPool(),
                null, entry.getQuantity(), false, CallerType.BIND));
        }

        return entitlements;
    }

    /**
     * Request an entitlement by product for a host system in
     * a host-guest relationship.  Allows getBestPoolsForHost
     * to choose products to bind.
     *
     * @param guest consumer requesting to have host entitled
     * @param host host consumer to entitle
     * @param entitleDate specific date to entitle by.
     * @return Entitlement
     * @throws EntitlementRefusedException if entitlement is refused
     */
    //
    // NOTE: after calling this method both entitlement pool and consumer
    // parameters
    // will most certainly be stale. beware!
    //
    @Override
    @Transactional
    public List<Entitlement> entitleByProductsForHost(Consumer guest, Consumer host,
            Date entitleDate, Collection<String> possiblePools)
        throws EntitlementRefusedException {
        List<Entitlement> entitlements = new LinkedList<Entitlement>();
        if (!host.getOwner().equals(guest.getOwner())) {
            log.debug("Host {} and guest {} have different owners", host.getUuid(), guest.getUuid());
            return entitlements;
        }
        Owner owner = host.getOwner();

        // Use the current date if one wasn't provided:
        if (entitleDate == null) {
            entitleDate = new Date();
        }

        List<PoolQuantity> bestPools = getBestPoolsForHost(guest, host,
            entitleDate, owner, null, possiblePools);

        if (bestPools == null) {
            log.info("No entitlements for host: {}", host.getUuid());
            return null;
        }

        // now make the entitlements
        for (PoolQuantity entry : bestPools) {
            entitlements.add(addOrUpdateEntitlement(host, entry.getPool(),
                null, entry.getQuantity(), false, CallerType.BIND));
        }

        return entitlements;
    }

    /**
     * Here we pick uncovered products from the guest where no virt-only
     * subscriptions exist, and have the host bind non-zero virt_limit
     * subscriptions in order to generate pools for the guest to bind later.
     *
     * @param guest whose products we want to provide
     * @param host to bind entitlements to
     * @param entitleDate
     * @param owner
     * @param serviceLevelOverride
     * @return PoolQuantity list to attempt to attach
     * @throws EntitlementRefusedException if unable to bind
     */
    @Override
    public List<PoolQuantity> getBestPoolsForHost(Consumer guest,
        Consumer host, Date entitleDate, Owner owner,
        String serviceLevelOverride, Collection<String> fromPools)
        throws EntitlementRefusedException {

        ValidationResult failedResult = null;
        log.debug("Looking up best pools for host: {}", host);

        Date activePoolDate = entitleDate;
        if (entitleDate == null) {
            activePoolDate = new Date();
        }
        PoolFilterBuilder poolFilter = new PoolFilterBuilder();
        poolFilter.addIdFilters(fromPools);
        List<Pool> allOwnerPools = this.listAvailableEntitlementPools(
            host, null, owner, (String) null, activePoolDate, true, false,
            poolFilter, null).getPageData();
        log.debug("Found {} total pools in org.", allOwnerPools.size());
        logPools(allOwnerPools);

        List<Pool> allOwnerPoolsForGuest = this.listAvailableEntitlementPools(
            guest, null, owner, (String) null, activePoolDate,
            true, false, poolFilter,
            null).getPageData();
        log.debug("Found {} total pools already available for guest", allOwnerPoolsForGuest.size());
        logPools(allOwnerPoolsForGuest);

        for (Entitlement ent : host.getEntitlements()) {
            //filter out pools that are attached, there is no need to
            //complete partial stacks, as they are already granting
            //virtual pools
            log.debug("Removing pool host is already entitled to: {}", ent.getPool());
            allOwnerPools.remove(ent.getPool());
        }
        List<Pool> filteredPools = new LinkedList<Pool>();

        ComplianceStatus guestCompliance = complianceRules.getStatus(guest, entitleDate,
                false);
        Set<String> tmpSet = new HashSet<String>();
        //we only want to heal red products, not yellow
        tmpSet.addAll(guestCompliance.getNonCompliantProducts());
        log.debug("Guest's non-compliant products: {}", Util.collectionToString(tmpSet));

        /*Do not attempt to create subscriptions for products that
          already have virt_only pools available to the guest */
        Set<String> productsToRemove = new HashSet<String>();
        for (Pool pool : allOwnerPoolsForGuest) {
            if (pool.getProduct().hasAttribute("virt_only") || pool.hasAttribute("virt_only")) {
                for (String prodId : tmpSet) {
                    if (pool.provides(prodId)) {
                        productsToRemove.add(prodId);
                    }
                }
            }
        }
        log.debug("Guest already will have virt-only pools to cover: {}",
                Util.collectionToString(productsToRemove));
        tmpSet.removeAll(productsToRemove);
        String[] productIds = tmpSet.toArray(new String [] {});

        if (log.isDebugEnabled()) {
            log.debug("Attempting host autobind for guest products: {}",
                    Util.collectionToString(tmpSet));
        }

        for (Pool pool : allOwnerPools) {
            boolean providesProduct = false;
            // Would parse the int here, but it can be 'unlimited'
            // and we only need to check that it's non-zero
            if (pool.getProduct().hasAttribute("virt_limit") &&
                    !pool.getProduct().getAttributeValue("virt_limit").equals("0")) {
                for (String productId : productIds) {
                    // If this is a derived pool, we need to see if the derived product
                    // provides anything for the guest, otherwise we use the parent.
                    if (pool.providesDerived(productId)) {
                        log.debug("Found virt_limit pool providing product {}: {}", productId, pool);
                        providesProduct = true;
                        break;
                    }
                }
            }
            if (providesProduct) {
                ValidationResult result = enforcer.preEntitlement(host,
                    pool, 1, CallerType.BEST_POOLS);

                if (result.hasErrors() || result.hasWarnings()) {
                    // Just keep the last one around, if we need it
                    failedResult = result;
                    if (log.isDebugEnabled()) {
                        log.debug("Pool filtered from candidates due to failed rule(s): {}", pool);
                        log.debug("  warnings: {}", Util.collectionToString(result.getWarnings()));
                        log.debug("  errors: {}", Util.collectionToString(result.getErrors()));
                    }
                }
                else {
                    filteredPools.add(pool);
                }
            }
        }

        // Only throw refused exception if we actually hit the rules:
        if (filteredPools.size() == 0 && failedResult != null) {
            throw new EntitlementRefusedException(failedResult);
        }
        ComplianceStatus hostCompliance = complianceRules.getStatus(host, entitleDate, false);

        log.debug("Host pools being sent to rules: {}", filteredPools.size());
        logPools(filteredPools);
        List<PoolQuantity> enforced = autobindRules.selectBestPools(host,
            productIds, filteredPools, hostCompliance, serviceLevelOverride,
            poolCurator.retrieveServiceLevelsForOwner(owner, true), true);

        if (log.isDebugEnabled()) {
            log.debug("Host selectBestPools returned {} pools: ", enforced.size());
            for (PoolQuantity poolQuantity : enforced) {
                log.debug("   " + poolQuantity.getPool());
            }
        }

        return enforced;
    }

    private void logPools(Collection<Pool> pools) {
        if (log.isDebugEnabled()) {
            for (Pool p : pools) {
                log.debug("   " + p);
            }
        }
    }

    @Override
    public List<PoolQuantity> getBestPools(Consumer consumer,
        String[] productIds, Date entitleDate, Owner owner,
        String serviceLevelOverride, Collection<String> fromPools)
        throws EntitlementRefusedException {

        boolean hasFromPools = fromPools != null && !fromPools.isEmpty();
        ValidationResult failedResult = null;

        Date activePoolDate = entitleDate;
        if (entitleDate == null) {
            activePoolDate = new Date();
        }

        PoolFilterBuilder poolFilter = new PoolFilterBuilder();
        poolFilter.addIdFilters(fromPools);
        List<Pool> allOwnerPools = this.listAvailableEntitlementPools(
            consumer, null, owner, (String) null, activePoolDate, true, false,
            poolFilter, null).getPageData();
        List<Pool> filteredPools = new LinkedList<Pool>();

        // We have to check compliance status here so we can replace an empty
        // array of product IDs with the array the consumer actually needs. (i.e. during
        // a healing request)
        ComplianceStatus compliance = complianceRules.getStatus(consumer, entitleDate, false);
        if (productIds == null || productIds.length == 0) {
            log.debug("No products specified for bind, checking compliance to see what is needed.");
            Set<String> tmpSet = new HashSet<String>();
            tmpSet.addAll(compliance.getNonCompliantProducts());
            tmpSet.addAll(compliance.getPartiallyCompliantProducts().keySet());
            productIds = tmpSet.toArray(new String [] {});
        }

        if (log.isDebugEnabled()) {
            log.debug("Attempting for products on date: {}", entitleDate);
            for (String productId : productIds) {
                log.debug("  " + productId);
            }
        }

        for (Pool pool : allOwnerPools) {
            boolean providesProduct = false;
            // If We want to complete partial stacks if possible,
            // even if they do not provide any products
            if (pool.getProduct().hasAttribute("stacking_id") &&
                    compliance.getPartialStacks().containsKey(
                        pool.getProduct().getAttributeValue("stacking_id"))) {
                providesProduct = true;
            }
            else {
                for (String productId : productIds) {
                    if (pool.provides(productId)) {
                        providesProduct = true;
                        break;
                    }
                }
            }
            if (providesProduct) {
                ValidationResult result = enforcer.preEntitlement(consumer,
                    pool, 1, CallerType.BEST_POOLS);

                if (result.hasErrors() || result.hasWarnings()) {
                    // Just keep the last one around, if we need it
                    failedResult = result;
                    log.debug("Pool filtered from candidates due to rules failure: {}", pool.getId());
                }
                else {
                    filteredPools.add(pool);
                }
            }
        }

        // Only throw refused exception if we actually hit the rules:
        if (filteredPools.size() == 0 && failedResult != null) {
            throw new EntitlementRefusedException(failedResult);
        }

        List<PoolQuantity> enforced = autobindRules.selectBestPools(consumer,
            productIds, filteredPools, compliance, serviceLevelOverride,
            poolCurator.retrieveServiceLevelsForOwner(owner, true), false);
        // Sort the resulting pools to avoid deadlocks
        if (enforced != null) {
            Collections.sort(enforced);
        }
        return enforced;
    }

    /**
     * Request an entitlement by pool.. If the entitlement cannot be granted,
     * null will be returned. TODO: Throw exception if entitlement not granted.
     * Report why.
     *
     * @param consumer consumer requesting to be entitled
     * @param pool entitlement pool to consume from
     * @return Entitlement
     * @throws EntitlementRefusedException if entitlement is refused
     */
    @Override
    @Transactional
    public Entitlement entitleByPool(Consumer consumer, Pool pool,
        Integer quantity) throws EntitlementRefusedException {
        return addOrUpdateEntitlement(consumer, pool, null, quantity,
            false, CallerType.BIND);
    }

    @Override
    @Transactional
    public Entitlement ueberCertEntitlement(Consumer consumer, Pool pool,
        Integer quantity) throws EntitlementRefusedException {
        return addOrUpdateEntitlement(consumer, pool, null, 1, true, CallerType.UNKNOWN);
    }

    @Override
    @Transactional
    public Entitlement adjustEntitlementQuantity(Consumer consumer,
        Entitlement entitlement, Integer quantity)
        throws EntitlementRefusedException {
        int change = quantity - entitlement.getQuantity();
        if (change == 0) {
            return entitlement;
        }
        return addOrUpdateEntitlement(consumer, entitlement.getPool(), entitlement,
            change, true, CallerType.UNKNOWN);
    }

    private Entitlement addOrUpdateEntitlement(Consumer consumer, Pool pool,
        Entitlement entitlement, Integer quantity, boolean generateUeberCert,
        CallerType caller)
        throws EntitlementRefusedException {
        // Because there are several paths to this one place where entitlements
        // are granted, we cannot be positive the caller obtained a lock on the
        // pool
        // when it was read. As such we're going to reload it with a lock
        // before starting this process.
        log.info("Locking pool: {}", pool.getId());
        pool = poolCurator.lockAndLoad(pool);

        if (quantity > 0) {
            log.info("Running pre-entitlement rules.");
            // XXX preEntitlement is run twice for new entitlement creation
            ValidationResult result = enforcer.preEntitlement(consumer, pool, quantity, caller);

            if (!result.isSuccessful()) {
                log.warn("Entitlement not granted: {}", result.getErrors().toString());
                throw new EntitlementRefusedException(result);
            }
        }
        EntitlementHandler handler = null;
        if (entitlement == null) {
            handler = new NewHandler();
        }
        else {
            handler = new UpdateHandler();
        }

        log.info("Processing entitlement.");
        entitlement = handler.handleEntitlement(consumer, pool, entitlement, quantity);
        // Persist the entitlement after it has been created.  It requires an ID in order to
        // create an entitlement-derived subpool
        log.info("Persisting entitlement.");
        handler.handleEntitlementPersist(entitlement);

        // The quantity is calculated at fetch time. We update it here
        // To reflect what we just added to the db.
        pool.setConsumed(pool.getConsumed() + quantity);
        if (consumer.getType().isManifest()) {
            pool.setExported(pool.getExported() + quantity);
        }
        PoolHelper poolHelper = new PoolHelper(this, entitlement);
        handler.handlePostEntitlement(consumer, poolHelper, entitlement);

        // Check consumer's new compliance status and save:
        complianceRules.getStatus(consumer, null, false, false);
        consumerCurator.update(consumer);

        handler.handleSelfCertificate(consumer, pool, entitlement, generateUeberCert);
        for (Entitlement regenEnt : entitlementCurator.listModifying(entitlement)) {
            // Lazily regenerate modified certificates:
            this.regenerateCertificatesOf(regenEnt, generateUeberCert, true);
        }

        // we might have changed the bonus pool quantities, lets find out.
        handler.handleBonusPools(pool, entitlement);

        log.info("Granted entitlement: {} from pool: {}", entitlement.getId(), pool.getId());
        return entitlement;
    }

    /**
     * This method will pull the bonus pools from a physical and make sure that
     *  the bonus pools are not over-consumed.
     *
     * @param consumer
     * @param pool
     */
    private void checkBonusPoolQuantities(Pool pool, Entitlement e) {
        for (Pool derivedPool :
                lookupOversubscribedBySubscriptionId(pool.getSubscriptionId(), e)) {
            if (!derivedPool.getId().equals(pool.getId()) &&
                derivedPool.getQuantity() != -1) {
                deleteExcessEntitlements(derivedPool);
            }
        }
    }

    /**
     * @param consumer
     * @param pool
     * @param e
     * @param mergedPool
     * @return
     */
    private EntitlementCertificate generateEntitlementCertificate(
        Pool pool, Entitlement e, boolean generateUeberCert) {

        Product product = pool.getProduct();

        try {
            return generateUeberCert ?
                entCertAdapter.generateUeberCert(e, product) :
                entCertAdapter.generateEntitlementCert(e, product);
        }
        catch (CertVersionConflictException cvce) {
            throw cvce;
        }
        catch (CertificateSizeException cse) {
            throw cse;
        }
        catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void regenerateEntitlementCertificates(Consumer consumer, boolean lazy) {
        log.info(
            "Regenerating #{}, entitlement certificates for consumer: {}",
            consumer.getEntitlements().size(), consumer
        );

        // TODO - Assumes only 1 entitlement certificate exists per entitlement
        this.regenerateCertificatesOf(consumer.getEntitlements(), lazy);
    }

    @Transactional
    void regenerateCertificatesOf(Iterable<Entitlement> iterable, boolean lazy) {
        for (Entitlement e : iterable) {
            regenerateCertificatesOf(e, false, lazy);
        }
    }

    @Transactional
    void regenerateCertificatesByEntIds(Iterable<String> iterable, boolean lazy) {
        for (String entId : iterable) {
            Entitlement e = entitlementCurator.find(entId);
            if (e != null) {
                regenerateCertificatesOf(e, false, lazy);
            }
            else {
                // If it has been deleted, that's fine, one less to regenerate
                log.info("Couldn't load Entitlement \"{}\" to regenerate, assuming deleted", entId);
            }
        }
    }

    /**
     * Used to regenerate certificates affected by a mass content promotion/demotion.
     *
     * WARNING: can be quite expensive, currently we must look up all entitlements in the
     * environment, all provided products for each entitlement, and check if any product
     * provides any of the modified content set IDs.
     *
     * @param e Environment where the content was promoted/demoted.
     * @param affectedContent List of content set IDs promoted/demoted.
     */
    @Override
    @Transactional
    public void regenerateCertificatesOf(Environment e, Set<String> affectedContent, boolean lazy) {
        log.info("Regenerating relevant certificates in environment: {}", e.getId());

        List<Entitlement> allEnvEnts = entitlementCurator.listByEnvironment(e);
        Set<Entitlement> entsToRegen = new HashSet<Entitlement>();
        for (Entitlement ent : allEnvEnts) {
            Product prod = productCurator.lookupById(ent.getOwner(), ent.getProductId());
            for (String contentId : affectedContent) {
                if (prod.hasContent(contentId)) {
                    entsToRegen.add(ent);
                }
            }

            // Now the provided products:
            for (Product provided : ent.getPool().getProvidedProducts()) {
                for (String contentId : affectedContent) {
                    if (provided.hasContent(contentId)) {
                        entsToRegen.add(ent);
                    }
                }
            }
        }

        log.info("Found {} certificates to regenerate.", entsToRegen.size());
        regenerateCertificatesOf(entsToRegen, lazy);
    }

    /**
     * @param e
     */
    @Override
    @Transactional
    public void regenerateCertificatesOf(Entitlement e, boolean ueberCertificate, boolean lazy) {

        if (lazy) {
            log.info("Marking certificates dirty for entitlement: {}", e);
            e.setDirty(true);
            return;
        }

        log.debug("Revoking entitlementCertificates of: {}", e);

        Entitlement tempE = new Entitlement();
        tempE.setCertificates(e.getCertificates());
        e.setCertificates(null);

        // below call creates new certificates and saves it to the backend.
        try {
            EntitlementCertificate generated = this.generateEntitlementCertificate(
                e.getPool(), e, ueberCertificate
            );

            e.setDirty(false);
            entitlementCurator.merge(e);
            for (EntitlementCertificate ec : tempE.getCertificates()) {
                log.debug("Deleting entitlementCertificate: #{}", ec.getId());
                this.entitlementCertificateCurator.delete(ec);
            }

            // send entitlement changed event.
            this.sink.queueEvent(this.eventFactory.entitlementChanged(e));
            log.debug("Generated entitlementCertificate: #{}", generated.getId());
        }
        catch (CertificateSizeException cse) {
            e.setCertificates(tempE.getCertificates());
            log.warn("The certificate cannot be regenerated at this time: {}", cse.getMessage());
        }
    }

    @Override
    @Transactional
    public void regenerateCertificatesOf(Owner owner, String productId, boolean lazy) {
        List<Pool> poolsForProduct = this.listAvailableEntitlementPools(null, null, owner,
            productId, new Date(), false, false, new PoolFilterBuilder(), null)
            .getPageData();

        for (Pool pool : poolsForProduct) {
            regenerateCertificatesOf(pool.getEntitlements(), lazy);
        }
    }

    /**
     * Remove the given entitlement and clean up.
     *
     * @param entitlement entitlement to remove
     * @param regenModified should we look for modified entitlements that are affected
     * and regenerated. False if we're mass deleting all the entitlements for a consumer
     * anyhow, true otherwise. Prevents a deadlock issue on mysql (at least).
     */
    @Transactional
    void removeEntitlement(Entitlement entitlement, boolean regenModified) {
        Consumer consumer = entitlement.getConsumer();
        Pool pool = entitlement.getPool();

        // Similarly to when we add an entitlement, lock the pool when we remove one, too.
        // This won't do anything for over/under consumption, but it will prevent
        // concurrency issues if someone else is operating on the pool.
        pool = poolCurator.lockAndLoad(pool);
        consumer.removeEntitlement(entitlement);

        // Look for pools referencing this entitlement as their source
        // entitlement and clean them up as well

        // we need to create a list of pools and entitlements to delete,
        // otherwise we are tampering with the loop iterator from inside
        // the loop (#811581)
        Set<Pool> deletablePools = new HashSet<Pool>();

        for (Pool p : poolCurator.listBySourceEntitlement(entitlement)) {
            for (Entitlement e : p.getEntitlements()) {
                this.revokeEntitlement(e);
            }

            deletablePools.add(p);
        }
        for (Pool dp : deletablePools) {
            deletePool(dp);
        }

        pool.getEntitlements().remove(entitlement);
        poolCurator.merge(pool);
        entitlementCurator.delete(entitlement);
        Event event = eventFactory.entitlementDeleted(entitlement);

        // The quantity is calculated at fetch time. We update it here
        // To reflect what we just removed from the db.
        pool.setConsumed(pool.getConsumed() - entitlement.getQuantity());

        if (consumer.getType().isManifest()) {
            pool.setExported(pool.getExported() - entitlement.getQuantity());
        }

        // Check for a single stacked sub pool as well. We'll need to either
        // update or delete the sub pool now that all other pools have been deleted.
        if (!"true".equals(pool.getAttributeValue("pool_derived")) &&
            pool.getProduct().hasAttribute("stacking_id")) {

            String stackId = pool.getProduct().getAttributeValue("stacking_id");
            Pool stackedSubPool = poolCurator.getSubPoolForStackId(consumer, stackId);
            if (stackedSubPool != null) {
                List<Entitlement> stackedEnts =
                    this.entitlementCurator.findByStackId(consumer, stackId);

                // If there are no stacked entitlements, we need to delete the
                // stacked sub pool, else we update it based on the entitlements
                // currently in the stack.
                if (stackedEnts.isEmpty()) {
                    deletePool(stackedSubPool);
                }
                else {
                    updatePoolFromStackedEntitlements(stackedSubPool, stackedEnts);
                    poolCurator.merge(stackedSubPool);
                }
            }
        }

        // post unbind actions
        PoolHelper poolHelper = new PoolHelper(this, entitlement);
        enforcer.postUnbind(consumer, poolHelper, entitlement);

        if (regenModified) {
            // Find all of the entitlements that modified the original entitlement,
            // and regenerate those to remove the content sets.
            // Lazy regeneration is ok here.
            this.regenerateCertificatesOf(entitlementCurator.listModifying(entitlement), true);
        }

        log.info("Revoked entitlement: {}", entitlement.getId());

        // If we don't care about updating other entitlements based on this one, we probably
        // don't care about updating compliance either.
        if (regenModified) {
            // Check consumer's new compliance status and save:
            complianceRules.getStatus(consumer);
        }

        sink.queueEvent(event);
    }

    @Override
    @Transactional
    public void revokeEntitlement(Entitlement entitlement) {
        removeEntitlement(entitlement, true);
    }

    @Override
    @Transactional
    public int revokeAllEntitlements(Consumer consumer) {
        int count = 0;
        for (Entitlement e : entitlementCurator.listByConsumer(consumer)) {
            removeEntitlement(e, false);
            count++;
        }
        // Rerun compliance after removing all entitlements
        complianceRules.getStatus(consumer);
        return count;
    }

    @Override
    @Transactional
    public int removeAllEntitlements(Consumer consumer) {
        int count = 0;
        for (Entitlement e : entitlementCurator.listByConsumer(consumer)) {
            removeEntitlement(e, false);
            count++;
        }
        return count;
    }

    /**
     * Cleanup entitlements and safely delete the given pool.
     *
     * @param pool
     */
    @Override
    @Transactional
    public void deletePool(Pool pool) {
        Event event = eventFactory.poolDeleted(pool);

        // Must do a full revoke for all entitlements:
        for (Entitlement e : poolCurator.entitlementsIn(pool)) {
            revokeEntitlement(e);
        }

        poolCurator.delete(pool);
        sink.queueEvent(event);
    }

    /**
     * Adjust the count of a pool. The caller does not have knowledge
     *   of the current quantity. It only determines how much to adjust.
     *
     * @param pool The pool.
     * @param adjust the long amount to adjust (+/-)
     * @return pool
     */
    @Override
    public Pool updatePoolQuantity(Pool pool, long adjust) {
        pool = poolCurator.lockAndLoad(pool);
        long newCount = pool.getQuantity() + adjust;
        if (newCount < 0) {
            newCount = 0;
        }
        pool.setQuantity(newCount);
        return poolCurator.merge(pool);
    }

    /**
     * Set the count of a pool. The caller sets the absolute quantity.
     *   Current use is setting unlimited bonus pool to -1 or 0.
     *
     * @param pool The pool.
     * @param set the long amount to set
     * @return pool
     */
    @Override
    public Pool setPoolQuantity(Pool pool, long set) {
        pool = poolCurator.lockAndLoad(pool);
        pool.setQuantity(set);
        return poolCurator.merge(pool);
    }

    @Override
    public void regenerateDirtyEntitlements(List<Entitlement> entitlements) {

        List<Entitlement> dirtyEntitlements = new ArrayList<Entitlement>();
        for (Entitlement e : entitlements) {
            if (e.getDirty()) {
                log.info("Found dirty entitlement to regenerate: {}", e);
                dirtyEntitlements.add(e);
            }
        }

        regenerateCertificatesOf(dirtyEntitlements, false);
    }

    @Override
    public Refresher getRefresher(SubscriptionServiceAdapter subAdapter) {
        return this.getRefresher(subAdapter, true);
    }

    @Override
    public Refresher getRefresher(SubscriptionServiceAdapter subAdapter, boolean lazy) {
        return new Refresher(this, subAdapter, lazy);
    }

    /**
     * EntitlementHandler
     */
    private interface EntitlementHandler {
        Entitlement handleEntitlement(Consumer consumer, Pool pool,
            Entitlement entitlement, int quantity);
        void handlePostEntitlement(Consumer consumer, PoolHelper poolHelper,
            Entitlement entitlement);
        void handleEntitlementPersist(Entitlement entitlement);
        void handleSelfCertificate(Consumer consumer, Pool pool,
            Entitlement entitlement, boolean generateUeberCert);
        void handleBonusPools(Pool pool, Entitlement entitlement);
    }

    /**
     * NewHandler
     */
    private class NewHandler implements EntitlementHandler{
        @Override
        public Entitlement handleEntitlement(Consumer consumer, Pool pool,
            Entitlement entitlement, int quantity) {
            Entitlement newEntitlement = new Entitlement(pool, consumer, quantity);
            consumer.addEntitlement(newEntitlement);
            pool.getEntitlements().add(newEntitlement);
            return newEntitlement;
        }
        @Override
        public void handlePostEntitlement(Consumer consumer, PoolHelper poolHelper,
            Entitlement entitlement) {

            Pool entPool = entitlement.getPool();
            if (entPool.isStacked()) {
                Pool pool =
                    poolCurator.getSubPoolForStackId(
                        entitlement.getConsumer(), entPool.getStackId());
                if (pool != null) {
                    poolRules.updatePoolFromStack(pool, new HashSet<Product>());
                    poolCurator.merge(pool);
                }
            }

            enforcer.postEntitlement(consumer, poolHelper, entitlement);
        }
        @Override
        public void handleEntitlementPersist(Entitlement entitlement) {
            entitlementCurator.create(entitlement);
        }
        @Override
        public void handleSelfCertificate(Consumer consumer, Pool pool,
            Entitlement entitlement, boolean generateUeberCert) {
            generateEntitlementCertificate(pool, entitlement, generateUeberCert);
        }
        @Override
        public void handleBonusPools(Pool pool, Entitlement entitlement) {
            checkBonusPoolQuantities(pool, entitlement);
        }
    }

    /**
     * UpdateHandler
     */
    private class UpdateHandler implements EntitlementHandler {
        @Override
        public Entitlement handleEntitlement(Consumer consumer, Pool pool,
            Entitlement entitlement, int quantity) {
            entitlement.setQuantity(entitlement.getQuantity() + quantity);
            return entitlement;
        }
        @Override
        public void handlePostEntitlement(Consumer consumer, PoolHelper poolHelper,
            Entitlement entitlement) {
        }
        @Override
        public void handleEntitlementPersist(Entitlement entitlement) {
            entitlementCurator.merge(entitlement);
        }
        @Override
        public void handleSelfCertificate(Consumer consumer, Pool pool,
            Entitlement entitlement, boolean generateUeberCert) {
            regenerateCertificatesOf(entitlement, generateUeberCert, true);
        }
        @Override
        public void handleBonusPools(Pool pool, Entitlement entitlement) {
            // TODO: I don't think a change here affects much anymore because sub-pools
            // are always just quantity = virt_limit, entitlement quantity doesn't matter.
            // TODO: what about legacy bonus pools after quantity changed on a manifest
            // consumer entitlement?
//            updatePoolsForSubscription(poolCurator.listBySourceEntitlement(entitlement),
//                subAdapter.getSubscription(pool.getSubscriptionId()), false,
//                new HashSet<Product>());
            checkBonusPoolQuantities(pool, entitlement);
        }
    }

    @Override
    public Page<List<Pool>> listAvailableEntitlementPools(Consumer consumer,
        ActivationKey key, Owner owner, String productId, Date activeOn,
        boolean activeOnly, boolean includeWarnings, PoolFilterBuilder filters,
        PageRequest pageRequest) {
        // Only postfilter if we have to
        boolean postFilter = consumer != null || key != null;
        Page<List<Pool>> page = this.poolCurator.listAvailableEntitlementPools(consumer,
            owner, productId, activeOn, activeOnly, filters, pageRequest, postFilter);

        if (consumer == null && key == null) {
            return page;
        }

        // If the consumer was specified, we need to filter out any
        // pools that the consumer will not be able to attach.
        // If querying for pools available to a specific consumer, we need
        // to do a rules pass to verify the entitlement will be granted.
        // Note that something could change between the time we list a pool as
        // available, and the consumer requests the actual entitlement, and the
        // request still could fail.
        List<Pool> resultingPools = page.getPageData();
        if (consumer != null) {
            resultingPools = enforcer.filterPools(
                consumer, resultingPools, includeWarnings);
        }
        if (key != null) {
            resultingPools = this.filterPoolsForActKey(
                key, resultingPools, includeWarnings);
        }

        // Set maxRecords once we are done filtering
        page.setMaxRecords(resultingPools.size());

        if (pageRequest != null && pageRequest.isPaging()) {
            resultingPools = poolCurator.takeSubList(pageRequest, resultingPools);
        }

        page.setPageData(resultingPools);
        return page;
    }

    /**
     * Creates a Subscription object using information derived from the specified pool. Used to
     * support deprecated API calls that still require a subscription.
     *
     * @param pool
     *  The pool from which to build a subscription
     *
     * @return
     *  a new subscription object derived from the specified pool.
     */
    @Override
    public Subscription fabricateSubscriptionFromPool(Pool pool) {
        if (pool == null) {
            throw new IllegalArgumentException("pool is null");
        }

        Subscription fabricated = new Subscription(
            pool.getOwner(),
            pool.getProduct(),
            pool.getProvidedProducts(),
            pool.getQuantity(),
            pool.getStartDate(),
            pool.getEndDate(),
            pool.getUpdated()
        );

        fabricated.setId(pool.getSubscriptionId());
        fabricated.setUpstreamEntitlementId(pool.getUpstreamEntitlementId());
        fabricated.setCdn(pool.getCdn());
        fabricated.setCertificate(pool.getCertificate());

        // TODO:
        // There's probably a fair amount of other stuff we need to migrate over to the
        // subscription. We should do that here.

        return fabricated;
    }

    @Override
    public List<Pool> getPoolsBySubscriptionId(String subscriptionId) {
        return this.poolCurator.getPoolsBySubscriptionId(subscriptionId);
    }

    @Override
    public Pool getMasterPoolBySubscriptionId(String subscriptionId) {
        return this.poolCurator.getMasterPoolBySubscriptionId(subscriptionId);
    }

    @Override
    public List<Pool> listMasterPools() {
        return this.poolCurator.listMasterPools();
    }

    @Override
    public Set<String> retrieveServiceLevelsForOwner(Owner owner, boolean exempt) {
        return poolCurator.retrieveServiceLevelsForOwner(owner, exempt);
    }

    @Override
    public List<Entitlement> findEntitlements(Pool pool) {
        return poolCurator.entitlementsIn(pool);
    }

    @Override
    public Pool findUeberPool(Owner owner) {
        return poolCurator.findUeberPool(owner);
    }

    @Override
    public List<Pool> listPoolsByOwner(Owner owner) {
        return poolCurator.listByOwner(owner);
    }

    public PoolUpdate updatePoolFromStack(Pool pool, Set<Product> changedProducts) {
        return poolRules.updatePoolFromStack(pool, changedProducts);
    }

    private PoolUpdate updatePoolFromStackedEntitlements(Pool pool,
        List<Entitlement> stackedEntitlements) {
        return poolRules.updatePoolFromStackedEntitlements(pool, stackedEntitlements,
                null);
    }

    public List<Pool> getOwnerSubPoolsForStackId(Owner owner, String stackId) {
        return poolCurator.getOwnerSubPoolsForStackId(owner, stackId);
    }

    private List<Pool> filterPoolsForActKey(ActivationKey key,
        List<Pool> pools, boolean includeWarnings) {
        List<Pool> filteredPools = new LinkedList<Pool>();
        for (Pool p : pools) {
            ValidationResult result = activationKeyRules.runPreActKey(key, p, null);
            if (result.isSuccessful() && (!result.hasWarnings() || includeWarnings)) {
                filteredPools.add(p);
            }
            else if (log.isDebugEnabled()) {
                log.debug("Omitting pool due to failed rules: {}", p.getId());
                if (result.hasErrors()) {
                    log.debug("\tErrors: {}", result.getErrors());
                }
                if (result.hasWarnings()) {
                    log.debug("\tWarnings: {}", result.getWarnings());
                }
            }
        }
        return filteredPools;
    }
}
