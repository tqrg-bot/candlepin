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


import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.candlepin.common.exceptions.BadRequestException;
import org.candlepin.model.Content;
import org.candlepin.model.ContentCurator;
import org.candlepin.model.Product;
import org.candlepin.model.ProductCurator;
import org.candlepin.model.ProductCertificate;
import org.candlepin.model.ProductCertificateCurator;
import org.candlepin.model.Owner;
import org.candlepin.model.OwnerCurator;
import org.candlepin.model.Subscription;
import org.candlepin.test.DatabaseTestFixture;

import org.junit.Test;
import org.xnap.commons.i18n.I18n;
import org.xnap.commons.i18n.I18nFactory;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.inject.Inject;


/**
 * ProductResourceTest
 */
public class ProductResourceTest extends DatabaseTestFixture {
    @Inject private ProductCertificateCurator productCertificateCurator;
    @Inject private ContentCurator contentCurator;
    @Inject private ProductResource productResource;
    @Inject private OwnerCurator ownerCurator;
    @Inject private ProductCurator productCurator;

    private Product createProduct(Owner owner) {
        String label = "test_product";
        String name = "Test Product";
        String variant = "server";
        String version = "1.0";
        String arch = "ALL";
        String type = "SVC";
        Product prod = new Product(label, name, owner, variant,
                version, arch, type);
        return prod;
    }

    @Test(expected = BadRequestException.class)
    public void testCreateProductResource() {
        Owner owner = ownerCurator.create(new Owner("Example-Corporation"));

        Product toSubmit = createProduct(owner);
        productResource.createProduct(toSubmit);
    }

    @Test(expected = BadRequestException.class)
    public void testCreateProductWithContent() {
        Owner owner = ownerCurator.create(new Owner("Example-Corporation"));

        Product toSubmit = createProduct(owner);
        String  contentHash = String.valueOf(
            Math.abs(Long.valueOf("test-content".hashCode())));
        Content testContent = new Content(owner, "test-content", contentHash,
                            "test-content-label", "yum", "test-vendor",
                             "test-content-url", "test-gpg-url", "test-arch");

        HashSet<Content> contentSet = new HashSet<Content>();
        testContent = contentCurator.create(testContent);
        contentSet.add(testContent);
        toSubmit.setContent(contentSet);

        productResource.createProduct(toSubmit);
    }

    @Test(expected = BadRequestException.class)
    public void testDeleteProductWithSubscriptions() {
        ProductCurator pc = mock(ProductCurator.class);
        I18n i18n = I18nFactory.getI18n(getClass(), Locale.US, I18nFactory.FALLBACK);
        ProductResource pr = new ProductResource(pc, null, null, null, null, i18n);
        Owner o = mock(Owner.class);
        Product p = mock(Product.class);
        when(pc.lookupById(eq(o), eq("10"))).thenReturn(p);
        Set<Subscription> subs = new HashSet<Subscription>();
        Subscription s = mock(Subscription.class);
        subs.add(s);
        when(pc.productHasSubscriptions(eq(p))).thenReturn(true);

        pr.deleteProduct("10");
    }

    @Test
    public void getProduct() {
        Owner owner = ownerCurator.create(new Owner("Example-Corporation"));
        Product product = productCurator.create(createProduct(owner));

        securityInterceptor.enable();

        // The returned product should be have the owner information removed.
        Product expected = (Product) product.clone();
        expected.setOwner(null);

        Product actual = productResource.getProduct(product.getId());
        assertEquals(actual, expected);
    }

    @Test
    public void getProductCertificate() {
        Owner owner = ownerCurator.create(new Owner("Example-Corporation"));
        Product p = productCurator.create(createProduct(owner));

        // ensure we check SecurityHole
        securityInterceptor.enable();

        ProductCertificate cert = new ProductCertificate();
        cert.setCert("some text");
        cert.setKey("some key");
        cert.setProduct(p);
        productCertificateCurator.create(cert);

        ProductCertificate cert1 = productResource.getProductCertificate(p.getId());
        assertEquals(cert, cert1);
    }
}
