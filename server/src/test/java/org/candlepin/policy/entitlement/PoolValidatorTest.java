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

import static org.junit.jupiter.api.Assertions.*;

import org.candlepin.model.Consumer;
import org.candlepin.model.ConsumerCapability;
import org.candlepin.model.ConsumerType;
import org.candlepin.model.Entitlement;
import org.candlepin.model.Pool;
import org.candlepin.model.Product;
import org.candlepin.policy.ValidationResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.xnap.commons.i18n.I18n;
import org.xnap.commons.i18n.I18nFactory;

import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Test suite for PoolValidator
 */
public class PoolValidatorTest {

    private Pool pool;
    private Product product;
    private Consumer consumer;
    private Enforcer.CallerType caller;
    private ConsumerType consumerType;
    private ValidationResult result;

    @BeforeEach
    public void setUp() {
        pool = new Pool();
        pool.setId("pool1");
        product = new Product();
        product.setId("product1");
        pool.setProduct(product);
        consumer = new Consumer();
        consumer.setId("consumer1");
        caller = Enforcer.CallerType.UNKNOWN;
        consumerType = new ConsumerType(ConsumerType.ConsumerTypeEnum.SYSTEM);
        result = new ValidationResult();
    }

    @Test
    public void testErrorKey() {
        I18n i18n = I18nFactory.getI18n(getClass(), "org.candlepin.i18n.Messages", Locale.US,
            I18nFactory.FALLBACK);
        Product product = new Product();
        product.setId("prod_id_1");
        assertEquals(
            "This unit has already had the subscription matching pool ID \"prod_id_1\" attached.",
            EntitlementRules.ErrorKeys.ALREADY_ATTACHED.buildErrorMessage(i18n, product.getId()));
    }

    @Nested
    @DisplayName("Global validate method")
    class GlobalValidate {

        @Test
        public void testGlobalValidateNoErrorsWhenConsumerIsManifest() {
            consumerType.setManifest(true);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .setQuantity(1)
                .build();

            PoolValidator.GLOBAL.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertFalse(result.hasWarningKeys());
        }

        @Test
        @SuppressWarnings("checkstyle:LineLength")
        public void testGlobalValidateNoErrorsWhenConsumerIsManifestButSupportsProductAndPoolHasDerivedProduct() {
            consumerType.setManifest(true);

            Product derived = new Product();
            derived.setId("derived_1");
            pool.setDerivedProduct(derived);

            Set<ConsumerCapability> capabilitySet = new HashSet<>();
            capabilitySet.add(new ConsumerCapability(consumer, "derived_product"));
            consumer.setCapabilities(capabilitySet);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .setQuantity(1)
                .build();

            PoolValidator.GLOBAL.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testGlobalValidateHasWarningWhenConsumerIsManifestAndPoolHasUnsupportedDerivedProduct() {
            consumerType.setManifest(true);

            Product derived = new Product();
            derived.setId("derived_1");
            pool.setDerivedProduct(derived);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .setQuantity(1)
                .build();

            PoolValidator.GLOBAL.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertTrue(result.hasWarningKeys());
            assertEquals(EntitlementRules.WarningKeys.DERIVED_PRODUCT_UNSUPPORTED_BY_CONSUMER,
                result.getWarningKeys().get(0));
        }

        @Test
        public void testGlobalValidateHasErrorWhenConsumerIsManifestAndPoolHasUnsupportedDerivedProduct() {
            consumerType.setManifest(true);

            Product derived = new Product();
            derived.setId("derived_1");
            pool.setDerivedProduct(derived);

            caller = Enforcer.CallerType.BEST_POOLS;

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .setQuantity(1)
                .build();

            PoolValidator.GLOBAL.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertEquals(EntitlementRules.ErrorKeys.DERIVED_PRODUCT_UNSUPPORTED_BY_CONSUMER,
                result.getErrorKeys().get(0));
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testGlobalValidateHasErrorWhenConsumerAlreadySubscribedToPool() {
            Entitlement existingEntitlement = new Entitlement();
            existingEntitlement.setPool(pool);
            consumer.addEntitlement(existingEntitlement);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .setQuantity(1)
                .build();

            PoolValidator.GLOBAL.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertEquals(EntitlementRules.ErrorKeys.ALREADY_ATTACHED, result.getErrorKeys().get(0));
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testGlobalValidateHasErrorWhenPoolIsNotMultiAndConsumerQuantityMoreThanOne() {
            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .setQuantity(33)
                .build();

            PoolValidator.GLOBAL.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertEquals(EntitlementRules.ErrorKeys.MULTI_ENTITLEMENT_UNSUPPORTED,
                result.getErrorKeys().get(0));
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testGlobalValidateHasErrorWhenPoolHasNoRequiredConsumerTypeButConsumerIsNotSystem() {
            consumerType = new ConsumerType(ConsumerType.ConsumerTypeEnum.PERSON);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .setQuantity(1)
                .build();

            PoolValidator.GLOBAL.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertEquals(EntitlementRules.ErrorKeys.CONSUMER_TYPE_MISMATCH, result.getErrorKeys().get(0));
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testGlobalValidateHasErrorWhenPoolIsRestrictedToSpecificUser() {
            consumer.setUsername("my_username");
            pool.setRestrictedToUsername("other_username");

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .setQuantity(1)
                .build();

            PoolValidator.GLOBAL.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertEquals(EntitlementRules.ErrorKeys.POOL_NOT_AVAILABLE_TO_USER, result.getErrorKeys().get(0));
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testGlobalValidateHasNoError() {
            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .setQuantity(1)
                .build();

            PoolValidator.GLOBAL.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertFalse(result.hasWarningKeys());
        }

    }


    @Nested
    @DisplayName("Architecture validate method")
    class ArchitectureValidate {

        @Test
        public void testArchitectureValidateNoWarningsWhenArchitectureMatches() {
            product.setAttribute(Product.Attributes.ARCHITECTURE, "x86");
            consumer.setFact(Consumer.Facts.ARCH, "x86");

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.ARCHITECTURE.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testArchitectureValidateHasWarningsWhenArchitectureDoesNotMatch() {
            product.setAttribute(Product.Attributes.ARCHITECTURE, "x86");
            consumer.setFact(Consumer.Facts.ARCH, "aarch64");

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.ARCHITECTURE.validate(data, result);

            assertTrue(result.hasWarningKeys());
            assertEquals(result.getWarningKeys().get(0), EntitlementRules.WarningKeys.ARCHITECTURE_MISMATCH);
        }

        @Test
        @SuppressWarnings("checkstyle:LineLength")
        public void testArchitectureValidateNoWarningsWhenArchitectureDoesNotMatchButConsumerTypeIsManifest() {
            product.setAttribute(Product.Attributes.ARCHITECTURE, "x86");
            consumer.setFact(Consumer.Facts.ARCH, "aarch64");
            consumerType.setManifest(true);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.ARCHITECTURE.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }
    }


    @Nested
    @DisplayName("VCPU validate method")
    class VcpuValidate {

        @Test
        public void testVcpuValidateHasWarningsWhenPoolCannotCoverNumberOfConsumerCores() {
            product.setAttribute(Product.Attributes.VCPU, "2");
            consumer.setFact(Consumer.Facts.CORES, "6");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "true");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.VCPU.validate(data, result);

            assertTrue(result.hasWarningKeys());
            assertEquals(EntitlementRules.WarningKeys.VCPU_NUMBER_UNSUPPORTED,
                result.getWarningKeys().get(0));
        }

        @Test
        public void testVcpuValidateNoWarningsWhenPoolCanCoverNumberOfConsumerCores() {
            product.setAttribute(Product.Attributes.VCPU, "6");
            consumer.setFact(Consumer.Facts.CORES, "6");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "true");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.VCPU.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testVcpuValidateNoWarningsWhenConsumerTypeIsManifest() {
            product.setAttribute(Product.Attributes.VCPU, "2");
            consumer.setFact(Consumer.Facts.CORES, "6");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "true");
            consumerType.setManifest(true);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.VCPU.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testVcpuValidateNoWarningsWhenConsumerIsNotGuest() {
            product.setAttribute(Product.Attributes.VCPU, "2");
            consumer.setFact(Consumer.Facts.CORES, "6");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "false");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.VCPU.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }


        @Test
        public void testVcpuValidateNoWarningsWhenConsumerHasNoVcpuSet() {
            product.setAttribute(Product.Attributes.VCPU, "2");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "true");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.VCPU.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testVcpuValidateNoWarningsWhenPoolHasStackingId() {
            product.setAttribute(Product.Attributes.VCPU, "2");
            consumer.setFact(Consumer.Facts.CORES, "6");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "true");
            consumerType.setManifest(false);

            product.setAttribute(Product.Attributes.STACKING_ID, "random_stack_id");

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.VCPU.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testVcpuValidateNoWarningsWhenVCPUNotSetOnConsumer() {
            product.setAttribute(Product.Attributes.VCPU, "2");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "true");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.VCPU.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }
    }


    @Nested
    @DisplayName("Sockets validate method")
    class SocketsValidate {

        @Test
        public void testSocketsValidateHasWarningsWhenPoolCannotCoverNumberOfConsumerSockets() {
            product.setAttribute(Product.Attributes.SOCKETS, "2");
            consumer.setFact(Consumer.Facts.SOCKETS, "6");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "false");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.SOCKETS.validate(data, result);

            assertTrue(result.hasWarningKeys());
            assertEquals(EntitlementRules.WarningKeys.SOCKET_NUMBER_UNSUPPORTED,
                result.getWarningKeys().get(0));
        }

        @Test
        public void testSocketsValidateNoWarningsWhenConsumerIsManifest() {
            product.setAttribute(Product.Attributes.SOCKETS, "2");
            consumer.setFact(Consumer.Facts.SOCKETS, "6");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "false");
            consumerType.setManifest(true);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.SOCKETS.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testSocketsValidateNoWarningsWhenConsumerIsGuest() {
            product.setAttribute(Product.Attributes.SOCKETS, "2");
            consumer.setFact(Consumer.Facts.SOCKETS, "6");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "true");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.SOCKETS.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testSocketsValidateNoWarningsWhenConsumerHasNoSocketsSet() {
            product.setAttribute(Product.Attributes.SOCKETS, "2");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "false");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.SOCKETS.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testSocketsValidateNoWarningsWhenPoolHasStackingIdSet() {
            product.setAttribute(Product.Attributes.STACKING_ID, "random_stack_id");
            product.setAttribute(Product.Attributes.SOCKETS, "2");
            consumer.setFact(Consumer.Facts.SOCKETS, "6");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "false");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.SOCKETS.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testSocketsValidateNoWarningsWhenPoolCanCoverTheConsumerSockets() {
            product.setAttribute(Product.Attributes.SOCKETS, "6");
            consumer.setFact(Consumer.Facts.SOCKETS, "6");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "false");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.SOCKETS.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }
    }


    @Nested
    @DisplayName("Cores validate method")
    class CoresValidate {

        @Test
        public void testCoresValidateHasWarningsWhenPoolCannotCoverNumberOfConsumerCores() {
            product.setAttribute(Product.Attributes.CORES, "2");
            consumer.setFact(Consumer.Facts.CORES, "6");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "false");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.CORES.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertTrue(result.hasWarningKeys());
            assertEquals(EntitlementRules.WarningKeys.CORE_NUMBER_UNSUPPORTED,
                result.getWarningKeys().get(0));
        }

        @Test
        public void testCoresValidateNoWarningsWhenConsumerIsGuestButNotManifest() {
            product.setAttribute(Product.Attributes.CORES, "2");
            consumer.setFact(Consumer.Facts.CORES, "6");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "true");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.CORES.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testCoresValidateNoWarningsWhenConsumerHasNoCoresSet() {
            product.setAttribute(Product.Attributes.CORES, "2");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "false");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.CORES.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testCoresValidateNoWarningsWhenPoolHasStackingIdSet() {
            product.setAttribute(Product.Attributes.STACKING_ID, "random_stack_id");
            product.setAttribute(Product.Attributes.CORES, "2");
            consumer.setFact(Consumer.Facts.CORES, "6");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "false");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.CORES.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testCoresValidateNoWarningsWhenPoolCanCoverTheConsumerCores() {
            product.setAttribute(Product.Attributes.CORES, "6");
            consumer.setFact(Consumer.Facts.CORES, "6");
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "false");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.CORES.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testCoresValidateHasWarningsWhenConsumerIsManifestAndNotCapableForCores() {
            product.setAttribute(Product.Attributes.CORES, "2");
            consumer.setFact(Consumer.Facts.CORES, "6");
            consumerType.setManifest(true);
            caller = Enforcer.CallerType.UNKNOWN;

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.CORES.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertTrue(result.hasWarningKeys());
            assertEquals(EntitlementRules.WarningKeys.CORES_UNSUPPORTED_BY_CONSUMER,
                result.getWarningKeys().get(0));
        }

        @Test
        @SuppressWarnings("checkstyle:LineLength")
        public void testCoresValidateHasErrorsWhenConsumerIsManifestAndNotCapableForCoresAndCallerIsBestPools() {
            product.setAttribute(Product.Attributes.CORES, "2");
            consumer.setFact(Consumer.Facts.CORES, "6");
            consumerType.setManifest(true);
            caller = Enforcer.CallerType.BEST_POOLS;

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.CORES.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertFalse(result.hasWarningKeys());
            assertEquals(EntitlementRules.ErrorKeys.CORES_UNSUPPORTED_BY_CONSUMER,
                result.getErrorKeys().get(0));
        }
    }


    @Nested
    @DisplayName("RAM validate method")
    class RamValidate {

        @Test
        public void testRAMValidateHasWarningsWhenPoolCannotCoverNumberOfConsumerRAM() {
            product.setAttribute(Product.Attributes.RAM, "2");
            consumer.setFact(Consumer.Facts.RAM, "19955492");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.RAM.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertTrue(result.hasWarningKeys());
            assertEquals(EntitlementRules.WarningKeys.RAM_NUMBER_UNSUPPORTED, result.getWarningKeys().get(0));
        }

        @Test
        public void testRAMValidateNoWarningsWhenConsumerHasNoRAMSet() {
            product.setAttribute(Product.Attributes.RAM, "2");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.RAM.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testRAMValidateNoWarningsWhenPoolHasStackingIdSet() {
            product.setAttribute(Product.Attributes.STACKING_ID, "random_stack_id");
            product.setAttribute(Product.Attributes.RAM, "2");
            consumer.setFact(Consumer.Facts.RAM, "19955492");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.RAM.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testRAMValidateNoWarningsWhenPoolCanCoverTheConsumerRAM() {
            product.setAttribute(Product.Attributes.RAM, "20");
            consumer.setFact(Consumer.Facts.RAM, "19955492");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.RAM.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testRAMValidateHasWarningsWhenConsumerIsManifestAndNotCapableForRAM() {
            product.setAttribute(Product.Attributes.RAM, "2");
            consumer.setFact(Consumer.Facts.RAM, "19955492");
            consumerType.setManifest(true);
            caller = Enforcer.CallerType.UNKNOWN;

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.RAM.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertTrue(result.hasWarningKeys());
            assertEquals(EntitlementRules.WarningKeys.RAM_UNSUPPORTED_BY_CONSUMER,
                result.getWarningKeys().get(0));
        }

        @Test
        public void testRAMValidateHasErrorsWhenConsumerIsManifestAndNotCapableForRAMAndCallerIsBestPools() {
            product.setAttribute(Product.Attributes.RAM, "2");
            consumer.setFact(Consumer.Facts.RAM, "19955492");
            consumerType.setManifest(true);
            caller = Enforcer.CallerType.BEST_POOLS;

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.RAM.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertFalse(result.hasWarningKeys());
            assertEquals(EntitlementRules.ErrorKeys.RAM_UNSUPPORTED_BY_CONSUMER,
                result.getErrorKeys().get(0));
        }
    }


    @Nested
    @DisplayName("Storage band validate method")
    class StorageBandValidate {

        @Test
        public void testStorageBandValidateHasWarningsWhenPoolCannotCoverNumberOfConsumerStorageBand() {
            product.setAttribute(Product.Attributes.STORAGE_BAND, "2");
            consumer.setFact(Consumer.Facts.STORAGE_BAND, "4");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.STORAGE_BAND.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertTrue(result.hasWarningKeys());
            assertEquals(EntitlementRules.WarningKeys.STORAGE_BAND_NUMBER_UNSUPPORTED,
                result.getWarningKeys().get(0));
        }

        @Test
        public void testStorageBandValidateNoWarningsWhenConsumerHasNoStorageBandSet() {
            product.setAttribute(Product.Attributes.STORAGE_BAND, "2");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.STORAGE_BAND.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testStorageBandValidateNoWarningsWhenPoolHasStackingIdSet() {
            product.setAttribute(Product.Attributes.STACKING_ID, "random_stack_id");
            product.setAttribute(Product.Attributes.STORAGE_BAND, "2");
            consumer.setFact(Consumer.Facts.STORAGE_BAND, "4");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.STORAGE_BAND.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testStorageBandValidateNoWarningsWhenPoolCanCoverTheConsumerStorageBand() {
            product.setAttribute(Product.Attributes.STORAGE_BAND, "4");
            consumer.setFact(Consumer.Facts.STORAGE_BAND, "4");
            consumerType.setManifest(false);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.STORAGE_BAND.validate(data, result);

            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testStorageBandValidateHasWarningsWhenConsumerIsManifestAndNotCapableForStorageBand() {
            product.setAttribute(Product.Attributes.STORAGE_BAND, "2");
            consumer.setFact(Consumer.Facts.STORAGE_BAND, "4");
            consumerType.setManifest(true);
            caller = Enforcer.CallerType.UNKNOWN;

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.STORAGE_BAND.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertTrue(result.hasWarningKeys());
            assertEquals(EntitlementRules.WarningKeys.STORAGE_BAND_UNSUPPORTED_BY_CONSUMER,
                result.getWarningKeys().get(0));
        }

        @Test
        @SuppressWarnings("checkstyle:LineLength")
        public void testStorageBandValidateHasErrorsWhenConsumerIsManifestAndNotCapableForStorageBandAndCallerIsBestPools() {
            product.setAttribute(Product.Attributes.STORAGE_BAND, "2");
            consumer.setFact(Consumer.Facts.STORAGE_BAND, "4");
            consumerType.setManifest(true);
            caller = Enforcer.CallerType.BEST_POOLS;

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.STORAGE_BAND.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertFalse(result.hasWarningKeys());
            assertEquals(EntitlementRules.ErrorKeys.STORAGE_BAND_UNSUPPORTED_BY_CONSUMER,
                result.getErrorKeys().get(0));
        }
    }


    @Nested
    @DisplayName("Virt only validate method")
    class VirtOnlyValidate {

        @Test
        public void testVirtOnlyValidateHasErrorWhenConsumerIsManifestAndPoolIsDerived() {
            product.setAttribute(Product.Attributes.VIRT_ONLY, "true");
            pool.setAttribute(Pool.Attributes.DERIVED_POOL, "true");
            consumerType.setManifest(true);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.VIRT_ONLY.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertEquals(EntitlementRules.ErrorKeys.RESTRICTED_POOL, result.getErrorKeys().get(0));
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testVirtOnlyValidateNoErrorWhenPoolIsNotVirtOnly() {
            product.setAttribute(Product.Attributes.VIRT_ONLY, "false");
            consumerType.setManifest(true);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.VIRT_ONLY.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testVirtOnlyValidateNoErrorWhenConsumerIsNotManifestAndIsGuest() {
            product.setAttribute(Product.Attributes.VIRT_ONLY, "true");
            consumerType.setManifest(false);
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "true");

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.VIRT_ONLY.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testVirtOnlyValidateHasErrorWhenConsumerIsNotGuestAndCallerIsBestPools() {
            product.setAttribute(Product.Attributes.VIRT_ONLY, "true");
            consumerType.setManifest(false);
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "false");
            caller = Enforcer.CallerType.BEST_POOLS;

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.VIRT_ONLY.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertEquals(EntitlementRules.ErrorKeys.VIRT_ONLY, result.getErrorKeys().get(0));
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testVirtOnlyValidateHasWarningWhenConsumerIsNotGuestAndCallerIsNotBestPoolsOrBind() {
            product.setAttribute(Product.Attributes.VIRT_ONLY, "true");
            consumerType.setManifest(false);
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "false");
            caller = Enforcer.CallerType.UNKNOWN;

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.VIRT_ONLY.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertTrue(result.hasWarningKeys());
            assertEquals(EntitlementRules.WarningKeys.VIRT_ONLY, result.getWarningKeys().get(0));
        }
    }

    @Nested
    @DisplayName("Physical only validate method")
    class PhysicalOnlyValidate {

        @Test
        public void testPhysicalOnlyValidateNoErrorWhenPoolIsNotPhysicalOnly() {
            pool.setAttribute(Pool.Attributes.PHYSICAL_ONLY, "false");
            consumerType.setManifest(false);
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "true");

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.PHYSICAL_ONLY.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testPhysicalOnlyValidateNoErrorWhenConsumerIsManifest() {
            pool.setAttribute(Pool.Attributes.PHYSICAL_ONLY, "true");
            consumerType.setManifest(true);
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "true");

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.PHYSICAL_ONLY.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testPhysicalOnlyValidateNoErrorWhenConsumerIsNotGuest() {
            pool.setAttribute(Pool.Attributes.PHYSICAL_ONLY, "true");
            consumerType.setManifest(false);
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "false");

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.PHYSICAL_ONLY.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testPhysicalOnlyValidateHasErrorWhenConsumerIsNotManifestAndIsGuestAndCallerIsBind() {
            pool.setAttribute(Pool.Attributes.PHYSICAL_ONLY, "true");
            consumerType.setManifest(false);
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "true");
            caller = Enforcer.CallerType.BIND;

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.PHYSICAL_ONLY.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertEquals(EntitlementRules.ErrorKeys.PHYSICAL_ONLY, result.getErrorKeys().get(0));
            assertFalse(result.hasWarningKeys());
        }

        @Test
        @SuppressWarnings("checkstyle:LineLength")
        public void testPhysicalOnlyValidateHasWarningWhenConsumerIsNotManifestAndIsGuestAndCallerIsUnknown() {
            pool.setAttribute(Pool.Attributes.PHYSICAL_ONLY, "true");
            consumerType.setManifest(false);
            consumer.setFact(Consumer.Facts.VIRT_IS_GUEST, "true");
            caller = Enforcer.CallerType.UNKNOWN;

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.PHYSICAL_ONLY.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertTrue(result.hasWarningKeys());
            assertEquals(EntitlementRules.WarningKeys.PHYSICAL_ONLY, result.getWarningKeys().get(0));
        }
    }


    @Nested
    @DisplayName("Unmapped guests only validate method")
    class UnmappedGuestsOnlyValidate {

        @Test
        public void testUnmappedGuestsOnlyValidateNoErrorWhenPoolIsNotUnmappedGuestsOnly() {
            pool.setAttribute(Pool.Attributes.UNMAPPED_GUESTS_ONLY, "false");
            pool.setStartDate(new Date(new Date().getTime() + 24L)); // 1 day in the future

            // Created more than a day ago
            consumer.setCreated(new Date(new Date().getTime() - 24L * 61L * 60L * 1000L));
            caller = Enforcer.CallerType.BIND;

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .setHostConsumer(new Consumer()) // has host
                .build();

            PoolValidator.UNMAPPED_GUESTS_ONLY.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testUnmappedGuestsOnlyValidateHasErrorWhenConsumerAlreadyHasAHost() {
            pool.setAttribute(Pool.Attributes.UNMAPPED_GUESTS_ONLY, "true");

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .setHostConsumer(new Consumer()) // has host
                .build();

            PoolValidator.UNMAPPED_GUESTS_ONLY.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertEquals(EntitlementRules.ErrorKeys.UNMAPPED_GUEST_RESTRICTED, result.getErrorKeys().get(0));
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testUnmappedGuestsOnlyValidateHasErrorWhenConsumerIsNotNewBorn() {
            pool.setAttribute(Pool.Attributes.UNMAPPED_GUESTS_ONLY, "true");
            // Created more than a day ago
            consumer.setCreated(new Date(new Date().getTime() - 24L * 61L * 60L * 1000L));

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.UNMAPPED_GUESTS_ONLY.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertEquals(EntitlementRules.ErrorKeys.VIRTUAL_GUEST_RESTRICTED, result.getErrorKeys().get(0));
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testUnmappedGuestsOnlyValidateHasErrorWhenPoolStartDateIsInFutureAndCallerIsBind() {
            pool.setAttribute(Pool.Attributes.UNMAPPED_GUESTS_ONLY, "true");
            consumer.setCreated(new Date()); // is newborn

            // 1 day in the future
            pool.setStartDate(new Date(new Date().getTime() + 24L * 60L * 60L * 1000L));
            caller = Enforcer.CallerType.BIND;

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.UNMAPPED_GUESTS_ONLY.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertEquals(EntitlementRules.ErrorKeys.TEMPORARY_FUTURE_POOL, result.getErrorKeys().get(0));
            assertFalse(result.hasWarningKeys());
        }
    }


    @Nested
    @DisplayName("Requires consumer validate method")
    class RequiresConsumerValidate {

        @Test
        public void testRequiresConsumerValidateHasErrorWhenConsumerIsManifest() {
            pool.setAttribute(Pool.Attributes.REQUIRES_CONSUMER, "some_consumer_uuid");
            consumerType.setManifest(true);

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.REQUIRES_CONSUMER.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertEquals(EntitlementRules.ErrorKeys.RESTRICTED_POOL, result.getErrorKeys().get(0));
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testRequiresConsumerValidateHasErrorWhenConsumerUuidDoesNotMatchWithPoolRequirement() {
            pool.setAttribute(Pool.Attributes.REQUIRES_CONSUMER, "some_consumer_uuid");
            consumerType.setManifest(false);
            consumer.setUuid("random_uuid");

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.REQUIRES_CONSUMER.validate(data, result);

            assertTrue(result.hasErrorKeys());
            assertEquals(EntitlementRules.ErrorKeys.CONSUMER_MISMATCH, result.getErrorKeys().get(0));
            assertFalse(result.hasWarningKeys());
        }

        @Test
        public void testRequiresConsumerValidateNoErrorWhenConsumerUuidMatchesWithPoolRequirement() {
            pool.setAttribute(Pool.Attributes.REQUIRES_CONSUMER, "same_uuid");
            consumerType.setManifest(false);
            consumer.setUuid("same_uuid");

            PoolValidationData data = new PoolValidationData.Builder()
                .setPool(pool)
                .setConsumer(consumer)
                .setCaller(caller)
                .setConsumerType(consumerType)
                .build();

            PoolValidator.REQUIRES_CONSUMER.validate(data, result);

            assertFalse(result.hasErrorKeys());
            assertFalse(result.hasWarningKeys());
        }
    }

    //TODO: REQUIRES_HOST unit test class
}
