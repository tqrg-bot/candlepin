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
package org.candlepin.policy.entitlement;

import org.candlepin.common.config.PropertyConverter;
import org.candlepin.model.Consumer;
import org.candlepin.model.ConsumerType;
import org.candlepin.model.Pool;
import org.candlepin.model.Product;
import org.candlepin.policy.FactValueCalculator;
import org.candlepin.policy.Util;
import org.candlepin.policy.ValidationResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pre-entitlement validation rules, to check if a pool should be considered for entitling a consumer.
 * Each Enum instance represents either a Product or Pool attribute and provides its own unique validation
 * method relevant to it.
 */
public enum PoolValidator {

    // This validator is always run (regardless of what attributes are present on the pool).
    GLOBAL("global") {

        @Override
        public void validate(PoolValidationData validationData, ValidationResult result) {
            Pool pool = validationData.getPool();
            Consumer consumer = validationData.getConsumer();
            Enforcer.CallerType caller = validationData.getCaller();
            ConsumerType consumerType = validationData.getConsumerType();
            int quantity = validationData.getQuantity();

            // Manifest should be able to extract by default.
            if (consumerType.isManifest()) {
                // Distributors should not be able to consume from pools with sub products
                // if they are not capable of supporting them.
                //
                // NOTE: We check for subProductId in the global space because it is not
                // a product attribute.
                if (pool.getDerivedProductId() != null && !consumer.isCapable("derived_product")) {
                    if (caller == Enforcer.CallerType.BEST_POOLS || caller == Enforcer.CallerType.BIND) {
                        result.addError(EntitlementRules.ErrorKeys.DERIVED_PRODUCT_UNSUPPORTED_BY_CONSUMER);
                    }
                    else {
                        result.addWarning(
                            EntitlementRules.WarningKeys.DERIVED_PRODUCT_UNSUPPORTED_BY_CONSUMER);
                    }
                }
            }
            else {
                Set<String> consumerPoolIds = consumer.getEntitlements()
                    .stream().map(e -> e.getPool().getId()).collect(Collectors.toSet());

                if (consumerPoolIds.contains(pool.getId()) && !pool.isMultiEnt()) {
                    result.addError(EntitlementRules.ErrorKeys.ALREADY_ATTACHED);
                }

                if (quantity > 1 && !pool.isMultiEnt()) {
                    result.addError(EntitlementRules.ErrorKeys.MULTI_ENTITLEMENT_UNSUPPORTED);
                }

                // If the product has no required consumer type, assume it is restricted to "system".
                // "hypervisor" type are essentially the same as "system".
                if (!pool.hasMergedProductAttribute(Pool.Attributes.REQUIRES_CONSUMER_TYPE) &&
                    !consumerType.getLabel().equals(ConsumerType.ConsumerTypeEnum.SYSTEM.getLabel()) &&
                    !consumerType.getLabel().equals(ConsumerType.ConsumerTypeEnum.HYPERVISOR.getLabel())) {

                    result.addError(EntitlementRules.ErrorKeys.CONSUMER_TYPE_MISMATCH);
                }

                if (pool.getRestrictedToUsername() != null &&
                    !pool.getRestrictedToUsername().equals(consumer.getUsername())) {

                    result.addError(EntitlementRules.ErrorKeys.POOL_NOT_AVAILABLE_TO_USER);
                }
            }
        }

    },
    ARCHITECTURE(Product.Attributes.ARCHITECTURE) {

        @Override
        public void validate(PoolValidationData validationData, ValidationResult result) {
            Pool pool = validationData.getPool();
            Consumer consumer = validationData.getConsumer();
            ConsumerType consumerType = validationData.getConsumerType();

            if (consumerType.isManifest()) {
                return;
            }

            if (!Util.architectureMatches(pool.getMergedProductAttribute(Product.Attributes.ARCHITECTURE),
                consumer.getFact(Consumer.Facts.ARCH), consumerType.getLabel())) {

                result.addWarning(EntitlementRules.WarningKeys.ARCHITECTURE_MISMATCH);
            }
        }

    },
    VCPU(Product.Attributes.VCPU) {

        @Override
        public void validate(PoolValidationData validationData, ValidationResult result) {
            Pool pool = validationData.getPool();
            Consumer consumer = validationData.getConsumer();
            ConsumerType consumerType = validationData.getConsumerType();

            if (!consumerType.isManifest()) {
                if (consumer.isGuest()) {
                    if (!pool.hasMergedProductAttribute(Product.Attributes.STACKING_ID)) {

                        int poolCores =
                            Integer.parseInt(pool.getMergedProductAttribute(Product.Attributes.VCPU));
                        int consumerCores = FactValueCalculator.getFact(Product.Attributes.VCPU, consumer);

                        if (poolCores > 0 && poolCores < consumerCores) {
                            result.addWarning(EntitlementRules.WarningKeys.VCPU_NUMBER_UNSUPPORTED);
                        }
                    }
                }
            }
            // Don't check if the manifest consumer is capable,
            // this attribute has existed for a while
            // now, we have just never checked it before
        }

    },
    SOCKETS(Product.Attributes.SOCKETS) {

        @Override
        public void validate(PoolValidationData validationData, ValidationResult result) {
            Pool pool = validationData.getPool();
            Consumer consumer = validationData.getConsumer();
            ConsumerType consumerType = validationData.getConsumerType();

            if (consumerType.isManifest() || consumer.isGuest()) {
                return;
            }

            if (!pool.hasMergedProductAttribute(Product.Attributes.STACKING_ID)) {

                int poolSockets =
                    Integer.parseInt(pool.getMergedProductAttribute(Product.Attributes.SOCKETS));
                int consumerSockets = FactValueCalculator.getFact(Product.Attributes.SOCKETS, consumer);

                if (poolSockets > 0 && (poolSockets < consumerSockets)) {
                    result.addWarning(EntitlementRules.WarningKeys.SOCKET_NUMBER_UNSUPPORTED);
                }
            }
        }

    },
    CORES(Product.Attributes.CORES) {

        @Override
        public void validate(PoolValidationData validationData, ValidationResult result) {
            Pool pool = validationData.getPool();
            Consumer consumer = validationData.getConsumer();
            ConsumerType consumerType = validationData.getConsumerType();
            Enforcer.CallerType caller = validationData.getCaller();

            if (!consumerType.isManifest()) {
                if (!consumer.isGuest()) {

                    if (!pool.hasMergedProductAttribute(Product.Attributes.STACKING_ID)) {

                        int poolCores =
                            Integer.parseInt(pool.getMergedProductAttribute(Product.Attributes.CORES));
                        int consumerCores = FactValueCalculator.getFact(Product.Attributes.CORES, consumer);

                        if (poolCores > 0 && poolCores < consumerCores) {
                            result.addWarning(EntitlementRules.WarningKeys.CORE_NUMBER_UNSUPPORTED);
                        }
                    }
                }
            }
            else {
                if (!consumer.isCapable(Product.Attributes.CORES)) {
                    if (caller == Enforcer.CallerType.BEST_POOLS || caller == Enforcer.CallerType.BIND) {
                        result.addError(EntitlementRules.ErrorKeys.CORES_UNSUPPORTED_BY_CONSUMER);
                    }
                    else {
                        result.addWarning(EntitlementRules.WarningKeys.CORES_UNSUPPORTED_BY_CONSUMER);
                    }
                }
            }
        }

    },
    RAM(Product.Attributes.RAM) {

        @Override
        public void validate(PoolValidationData validationData, ValidationResult result) {
            Pool pool = validationData.getPool();
            Consumer consumer = validationData.getConsumer();
            ConsumerType consumerType = validationData.getConsumerType();
            Enforcer.CallerType caller = validationData.getCaller();

            if (!consumerType.isManifest()) {
                if (!pool.hasMergedProductAttribute(Product.Attributes.STACKING_ID)) {

                    int productRam = Integer.parseInt(pool.getMergedProductAttribute(Product.Attributes.RAM));
                    log.debug("Product has {}GB of RAM", productRam);
                    int consumerRam = FactValueCalculator.getFact(Product.Attributes.RAM, consumer);
                    log.debug("Consumer has {}GB of RAM.", consumerRam);

                    if (consumerRam > productRam) {
                        result.addWarning(EntitlementRules.WarningKeys.RAM_NUMBER_UNSUPPORTED);
                    }
                }

            }
            else {
                if (!consumer.isCapable(Product.Attributes.RAM)) {
                    if (caller == Enforcer.CallerType.BEST_POOLS || caller == Enforcer.CallerType.BIND) {
                        result.addError(EntitlementRules.ErrorKeys.RAM_UNSUPPORTED_BY_CONSUMER);
                    }
                    else {
                        result.addWarning(EntitlementRules.WarningKeys.RAM_UNSUPPORTED_BY_CONSUMER);
                    }
                }
            }
        }

    },
    STORAGE_BAND(Product.Attributes.STORAGE_BAND) {

        @Override
        public void validate(PoolValidationData validationData, ValidationResult result) {
            Pool pool = validationData.getPool();
            Consumer consumer = validationData.getConsumer();
            ConsumerType consumerType = validationData.getConsumerType();
            Enforcer.CallerType caller = validationData.getCaller();

            if (!consumerType.isManifest()) {
                if (!pool.hasMergedProductAttribute(Product.Attributes.STACKING_ID)) {

                    int consumerBandUsage =
                        FactValueCalculator.getFact(Product.Attributes.STORAGE_BAND, consumer);
                    log.debug("Consumer is using {}TB of storage.", consumerBandUsage);
                    int productBandStorage =
                        Integer.parseInt(pool.getMergedProductAttribute(Product.Attributes.STORAGE_BAND));
                    log.debug("Product has {}TB of storage.", productBandStorage);

                    if (consumerBandUsage > productBandStorage) {
                        result.addWarning(EntitlementRules.WarningKeys.STORAGE_BAND_NUMBER_UNSUPPORTED);
                    }
                }
            }
            else {
                if (!consumer.isCapable(Product.Attributes.STORAGE_BAND)) {
                    if (caller == Enforcer.CallerType.BEST_POOLS || caller == Enforcer.CallerType.BIND) {
                        result.addError(EntitlementRules.ErrorKeys.STORAGE_BAND_UNSUPPORTED_BY_CONSUMER);
                    }
                    else {
                        result.addWarning(EntitlementRules.WarningKeys.STORAGE_BAND_UNSUPPORTED_BY_CONSUMER);
                    }
                }
            }
        }

    },
    VIRT_ONLY(Product.Attributes.VIRT_ONLY) {

        @Override
        public void validate(PoolValidationData validationData, ValidationResult result) {
            Pool pool = validationData.getPool();
            Consumer consumer = validationData.getConsumer();
            ConsumerType consumerType = validationData.getConsumerType();
            Enforcer.CallerType caller = validationData.getCaller();

            boolean isVirtPool =
                PropertyConverter.toBoolean(pool.getMergedAttribute(Pool.Attributes.VIRT_ONLY));
            boolean isDerivedPool =
                PropertyConverter.toBoolean(pool.getMergedAttribute(Pool.Attributes.DERIVED_POOL));

            if (isVirtPool) {
                if (consumerType.isManifest()) {
                    if (isDerivedPool) {
                        result.addError(EntitlementRules.ErrorKeys.RESTRICTED_POOL);
                    }
                }
                else if (!consumer.isGuest()) {
                    if (caller == Enforcer.CallerType.BEST_POOLS || caller == Enforcer.CallerType.BIND) {
                        result.addError(EntitlementRules.ErrorKeys.VIRT_ONLY);
                    }
                    else {
                        result.addWarning(EntitlementRules.WarningKeys.VIRT_ONLY);
                    }
                }
            }
        }

    },
    PHYSICAL_ONLY(Pool.Attributes.PHYSICAL_ONLY) {

        @Override
        public void validate(PoolValidationData validationData, ValidationResult result) {
            Pool pool = validationData.getPool();
            Consumer consumer = validationData.getConsumer();
            ConsumerType consumerType = validationData.getConsumerType();
            Enforcer.CallerType caller = validationData.getCaller();

            boolean isPhysicalOnly =
                PropertyConverter.toBoolean(pool.getMergedAttribute(Pool.Attributes.PHYSICAL_ONLY));

            if (isPhysicalOnly) {
                if (!consumerType.isManifest() && consumer.isGuest()) {
                    if (caller == Enforcer.CallerType.BEST_POOLS || caller == Enforcer.CallerType.BIND) {
                        result.addError(EntitlementRules.ErrorKeys.PHYSICAL_ONLY);
                    }
                    else {
                        result.addWarning(EntitlementRules.WarningKeys.PHYSICAL_ONLY);
                    }
                }
            }
        }

    },
    UNMAPPED_GUESTS_ONLY(Pool.Attributes.UNMAPPED_GUESTS_ONLY) {

        @Override
        public void validate(PoolValidationData validationData, ValidationResult result) {
            Pool pool = validationData.getPool();
            Consumer consumer = validationData.getConsumer();
            Enforcer.CallerType caller = validationData.getCaller();
            Consumer hostConsumer = validationData.getHostConsumer();

            boolean isUnmappedGuestPool = PropertyConverter.toBoolean(
                pool.getMergedAttribute(Pool.Attributes.UNMAPPED_GUESTS_ONLY));

            if (isUnmappedGuestPool) {
                if (hostConsumer != null){
                    /* We want to hide the temporary pools completely if the consumer can't use
                     * them.  Using an error instead of a warning keeps the pool from appearing in
                     * the results of a subscription-manager list --available --all */
                    result.addError(EntitlementRules.ErrorKeys.UNMAPPED_GUEST_RESTRICTED);
                }

                if (!consumer.isNewborn()) {
                    result.addError(EntitlementRules.ErrorKeys.VIRTUAL_GUEST_RESTRICTED);
                }

                Date now = new Date();
                if (caller == Enforcer.CallerType.BIND && now.before(pool.getStartDate())) {
                    result.addError(EntitlementRules.ErrorKeys.TEMPORARY_FUTURE_POOL);
                }
            }
        }

    },
    REQUIRES_CONSUMER(Pool.Attributes.REQUIRES_CONSUMER) {

        @Override
        public void validate(PoolValidationData validationData, ValidationResult result) {
            Pool pool = validationData.getPool();
            Consumer consumer = validationData.getConsumer();
            ConsumerType consumerType = validationData.getConsumerType();

            // requires_consumer pools not available to manifest
            if (consumerType.isManifest()) {
                result.addError(EntitlementRules.ErrorKeys.RESTRICTED_POOL);
                return;
            }

            if (!consumer.getUuid().equals(pool.getMergedAttribute(Pool.Attributes.REQUIRES_CONSUMER))) {
                result.addError(EntitlementRules.ErrorKeys.CONSUMER_MISMATCH);
            }
        }

    },
    REQUIRES_HOST(Pool.Attributes.REQUIRES_HOST) {

        @Override
        public void validate(PoolValidationData validationData, ValidationResult result) {
            Pool pool = validationData.getPool();
            Consumer consumer = validationData.getConsumer();
            ConsumerType consumerType = validationData.getConsumerType();
            Consumer hostConsumer = validationData.getHostConsumer();

            // requires_host derived pools not available to manifest
            if (consumerType.isManifest()) {
                result.addError(EntitlementRules.ErrorKeys.RESTRICTED_POOL);
                return;
            }

            if (!consumer.hasFact(Consumer.Facts.VIRT_UUID)) {
                result.addError(EntitlementRules.ErrorKeys.VIRT_ONLY);
                return;
            }

            if (hostConsumer == null ||
                !hostConsumer.getUuid().equals(pool.getMergedAttribute(Pool.Attributes.REQUIRES_HOST))) {

                result.addError(EntitlementRules.ErrorKeys.VIRT_HOST_MISMATCH);
            }
        }

    };
//    REQUIRES_CONSUMER_TYPE,
//    INSTANCE_MULTIPLIER,

    private static final Logger log = LoggerFactory.getLogger(PoolValidator.class);

    private String attributeKey;

    PoolValidator(String attributeKey) {
        this.attributeKey = attributeKey;
    }

    /**
     * To be implemented by instances of PoolValidator enums.
     * @param validationData the data required for validation such as pool, consumer, caller, and others.
     * @param result a result which holds lists of errors and warnings produced by the validation.
     */
    public abstract void validate(PoolValidationData validationData, ValidationResult result);

    /**
     * Returns the Product or Pool attribute that the instance represents.
     * @return the Product or Pool attribute that the instance represents.
     */
    public String getAttributeKey() {
        return this.attributeKey;
    }
}
