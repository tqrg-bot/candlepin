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
package org.candlepin.guice;

import org.candlepin.audit.AMQPBusPublisher;
import org.candlepin.audit.EventSink;
import org.candlepin.audit.EventSinkImpl;
import org.candlepin.auth.Principal;
import org.candlepin.common.config.Configuration;
import org.candlepin.common.config.ConfigurationPrefixes;
import org.candlepin.common.exceptions.mappers.BadRequestExceptionMapper;
import org.candlepin.common.exceptions.mappers.CandlepinExceptionMapper;
import org.candlepin.common.exceptions.mappers.DefaultOptionsMethodExceptionMapper;
import org.candlepin.common.exceptions.mappers.FailureExceptionMapper;
import org.candlepin.common.exceptions.mappers.InternalServerErrorExceptionMapper;
import org.candlepin.common.exceptions.mappers.JAXBMarshalExceptionMapper;
import org.candlepin.common.exceptions.mappers.JAXBUnmarshalExceptionMapper;
import org.candlepin.common.exceptions.mappers.MethodNotAllowedExceptionMapper;
import org.candlepin.common.exceptions.mappers.NoLogWebApplicationExceptionMapper;
import org.candlepin.common.exceptions.mappers.NotAcceptableExceptionMapper;
import org.candlepin.common.exceptions.mappers.NotFoundExceptionMapper;
import org.candlepin.common.exceptions.mappers.ReaderExceptionMapper;
import org.candlepin.common.exceptions.mappers.RollbackExceptionMapper;
import org.candlepin.common.exceptions.mappers.RuntimeExceptionMapper;
import org.candlepin.common.exceptions.mappers.UnauthorizedExceptionMapper;
import org.candlepin.common.exceptions.mappers.UnsupportedMediaTypeExceptionMapper;
import org.candlepin.common.exceptions.mappers.ValidationExceptionMapper;
import org.candlepin.common.exceptions.mappers.WebApplicationExceptionMapper;
import org.candlepin.common.exceptions.mappers.WriterExceptionMapper;
import org.candlepin.common.guice.JPAInitializer;
import org.candlepin.common.resteasy.interceptor.DynamicFilterInterceptor;
import org.candlepin.common.resteasy.interceptor.LinkHeaderPostInterceptor;
import org.candlepin.common.resteasy.interceptor.PageRequestInterceptor;
import org.candlepin.common.validation.CandlepinMessageInterpolator;
import org.candlepin.config.ConfigProperties;
import org.candlepin.controller.CandlepinPoolManager;
import org.candlepin.controller.CrlGenerator;
import org.candlepin.controller.Entitler;
import org.candlepin.controller.PoolManager;
import org.candlepin.model.UeberCertificateGenerator;
import org.candlepin.pinsetter.core.GuiceJobFactory;
import org.candlepin.pinsetter.core.PinsetterJobListener;
import org.candlepin.pinsetter.core.PinsetterKernel;
import org.candlepin.pinsetter.tasks.CertificateRevocationListTask;
import org.candlepin.pinsetter.tasks.EntitlerJob;
import org.candlepin.pinsetter.tasks.ExportCleaner;
import org.candlepin.pinsetter.tasks.JobCleaner;
import org.candlepin.pinsetter.tasks.RefreshPoolsJob;
import org.candlepin.pinsetter.tasks.SweepBarJob;
import org.candlepin.pinsetter.tasks.UnpauseJob;
import org.candlepin.pki.PKIReader;
import org.candlepin.pki.PKIUtility;
import org.candlepin.pki.impl.BouncyCastlePKIReader;
import org.candlepin.pki.impl.BouncyCastlePKIUtility;
import org.candlepin.policy.criteria.CriteriaRules;
import org.candlepin.policy.js.JsRunner;
import org.candlepin.policy.js.JsRunnerProvider;
import org.candlepin.policy.js.entitlement.Enforcer;
import org.candlepin.policy.js.entitlement.EntitlementRules;
import org.candlepin.policy.js.entitlement.EntitlementRulesTranslator;
import org.candlepin.policy.js.pool.PoolRules;
import org.candlepin.resource.ActivationKeyContentOverrideResource;
import org.candlepin.resource.ActivationKeyResource;
import org.candlepin.resource.AdminResource;
import org.candlepin.resource.AtomFeedResource;
import org.candlepin.resource.CdnResource;
import org.candlepin.resource.CertificateSerialResource;
import org.candlepin.resource.ConsumerContentOverrideResource;
import org.candlepin.resource.ConsumerResource;
import org.candlepin.resource.ConsumerTypeResource;
import org.candlepin.resource.ContentResource;
import org.candlepin.resource.CrlResource;
import org.candlepin.resource.DeletedConsumerResource;
import org.candlepin.resource.DistributorVersionResource;
import org.candlepin.resource.EntitlementResource;
import org.candlepin.resource.EnvironmentResource;
import org.candlepin.resource.EventResource;
import org.candlepin.resource.GuestIdResource;
import org.candlepin.resource.HypervisorResource;
import org.candlepin.resource.JobResource;
import org.candlepin.resource.OwnerContentResource;
import org.candlepin.resource.OwnerProductResource;
import org.candlepin.resource.OwnerResource;
import org.candlepin.resource.PoolResource;
import org.candlepin.resource.ProductResource;
import org.candlepin.resource.RoleResource;
import org.candlepin.resource.RootResource;
import org.candlepin.resource.RulesResource;
import org.candlepin.resource.StatisticResource;
import org.candlepin.resource.StatusResource;
import org.candlepin.resource.SubscriptionResource;
import org.candlepin.resource.UserResource;
import org.candlepin.resteasy.JsonProvider;
import org.candlepin.resteasy.interceptor.AuthInterceptor;
import org.candlepin.resteasy.interceptor.PinsetterAsyncInterceptor;
import org.candlepin.resteasy.interceptor.VersionPostInterceptor;
import org.candlepin.service.UniqueIdGenerator;
import org.candlepin.service.impl.DefaultUniqueIdGenerator;
import org.candlepin.sync.ConsumerExporter;
import org.candlepin.sync.ConsumerTypeExporter;
import org.candlepin.sync.EntitlementCertExporter;
import org.candlepin.sync.Exporter;
import org.candlepin.sync.MetaExporter;
import org.candlepin.sync.RulesExporter;
import org.candlepin.util.DateSource;
import org.candlepin.util.DateSourceImpl;
import org.candlepin.util.ExpiryDateFunction;
import org.candlepin.util.X509ExtensionUtil;

import com.google.common.base.Function;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.matcher.Matcher;
import com.google.inject.matcher.Matchers;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.google.inject.persist.Transactional;
import com.google.inject.persist.jpa.JpaPersistModule;
import com.google.inject.servlet.RequestScoped;

import org.hibernate.cfg.beanvalidation.BeanValidationEventListener;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.HibernateValidatorConfiguration;
import org.quartz.JobListener;
import org.quartz.spi.JobFactory;
import org.xnap.commons.i18n.I18n;

import java.lang.reflect.AnnotatedElement;
import java.util.Properties;

import javax.inject.Provider;
import javax.validation.MessageInterpolator;
import javax.validation.Validation;
import javax.validation.ValidatorFactory;

/**
 * CandlepinModule
 */
public class CandlepinModule extends AbstractModule {
    private Configuration config;

    public CandlepinModule(Configuration config) {
        this.config = config;
    }

    @Override
    public void configure() {
        // Bindings for our custom scope
        CandlepinSingletonScope singletonScope = new CandlepinSingletonScope();
        bindScope(CandlepinSingletonScoped.class, singletonScope);
        bind(CandlepinSingletonScope.class).toInstance(singletonScope);

        bind(I18n.class).toProvider(I18nProvider.class);
        bind(BeanValidationEventListener.class).toProvider(
                ValidationListenerProvider.class);
        bind(MessageInterpolator.class).to(CandlepinMessageInterpolator.class);

        configureJPA();

        bind(PKIUtility.class).to(BouncyCastlePKIUtility.class)
            .asEagerSingleton();
        bind(PKIReader.class).to(BouncyCastlePKIReader.class)
            .asEagerSingleton();
        bind(X509ExtensionUtil.class);
        bind(CrlGenerator.class);
        bind(ConsumerResource.class);
        bind(ConsumerContentOverrideResource.class);
        bind(ActivationKeyContentOverrideResource.class);
        bind(HypervisorResource.class);
        bind(ConsumerTypeResource.class);
        bind(ContentResource.class);
        bind(AtomFeedResource.class);
        bind(EventResource.class);
        bind(PoolResource.class);
        bind(EntitlementResource.class);
        bind(OwnerResource.class);
        bind(OwnerProductResource.class);
        bind(OwnerContentResource.class);
        bind(RoleResource.class);
        bind(RootResource.class);
        bind(ProductResource.class);
        bind(SubscriptionResource.class);
        bind(ActivationKeyResource.class);
        bind(CertificateSerialResource.class);
        bind(CrlResource.class);
        bind(JobResource.class);
        bind(DateSource.class).to(DateSourceImpl.class).asEagerSingleton();
        bind(Enforcer.class).to(EntitlementRules.class);
        bind(EntitlementRulesTranslator.class);
        bind(PoolManager.class).to(CandlepinPoolManager.class);
        bind(PoolRules.class);
        bind(CriteriaRules.class);
        bind(Entitler.class);
        bind(RulesResource.class);
        bind(AdminResource.class);
        bind(StatusResource.class);
        bind(EnvironmentResource.class);
        bind(StatisticResource.class);
        bind(UnsupportedMediaTypeExceptionMapper.class);
        bind(UnauthorizedExceptionMapper.class);
        bind(NotFoundExceptionMapper.class);
        bind(NotAcceptableExceptionMapper.class);
        bind(NoLogWebApplicationExceptionMapper.class);
        bind(MethodNotAllowedExceptionMapper.class);
        bind(InternalServerErrorExceptionMapper.class);
        bind(DefaultOptionsMethodExceptionMapper.class);
        bind(BadRequestExceptionMapper.class);
        bind(RollbackExceptionMapper.class);
        bind(ValidationExceptionMapper.class);
        bind(WebApplicationExceptionMapper.class);
        bind(FailureExceptionMapper.class);
        bind(ReaderExceptionMapper.class);
        bind(WriterExceptionMapper.class);
        bind(CandlepinExceptionMapper.class);
        bind(RuntimeExceptionMapper.class);
        bind(JAXBUnmarshalExceptionMapper.class);
        bind(JAXBMarshalExceptionMapper.class);
        bind(Principal.class).toProvider(PrincipalProvider.class);
        bind(JsRunnerProvider.class).asEagerSingleton();
        bind(JsRunner.class).toProvider(JsRunnerProvider.class);
        bind(UserResource.class);
        bind(UniqueIdGenerator.class).to(DefaultUniqueIdGenerator.class);
        bind(DistributorVersionResource.class);
        bind(DeletedConsumerResource.class);
        bind(CdnResource.class);
        bind(GuestIdResource.class);

        configureInterceptors();
        bind(JsonProvider.class);

        bind(EventSink.class).annotatedWith(Names.named("RequestSink"))
            .to(EventSinkImpl.class).in(RequestScoped.class);
        bind(EventSink.class).annotatedWith(Names.named("PinsetterSink"))
            .to(EventSinkImpl.class).in(PinsetterJobScoped.class);
        bind(EventSink.class).toProvider(EventSinkProvider.class);

        configurePinsetter();

        configureExporter();

        // Async Jobs
        bind(RefreshPoolsJob.class);
        bind(EntitlerJob.class);

        // UeberCerts
        bind(UeberCertificateGenerator.class);

        // flexible end date for identity certificates
        bind(Function.class).annotatedWith(Names.named("endDateGenerator"))
            .to(ExpiryDateFunction.class).in(Singleton.class);

        // only initialize if we've enabled AMQP integration
        if (config.getBoolean(ConfigProperties.AMQP_INTEGRATION_ENABLED)) {
            configureAmqp();
        }

        configureMethodInterceptors();

    }

    private void configureMethodInterceptors() {
        // Match methods on classes in org.candlepin.resource as long as neither class
        // nor method
        // is annotated with transactional.  This way the default @Transactional
        // annotation we use
        // by default may be overridden with more specific options,
        // For example: @Transactional(rollbackOn = IOException.class)
        // in a resource will produce the desired behavior
        bind(TransactionalInvoker.class);
        CandlepinResourceTxnInterceptor txni = new CandlepinResourceTxnInterceptor();
        // Creates the nested object with a @Transactional annotation
        requestInjection(txni);
        Matcher<AnnotatedElement> notMarked = Matchers.not(
                Matchers.annotatedWith(Transactional.class)).
                and(Matchers.not(Matchers.annotatedWith(NonTransactional.class)));
        bindInterceptor(Matchers.inSubpackage("org.candlepin.resource").and(notMarked),
                notMarked, txni);
    }

    @Provides @Named("ValidationProperties")
    protected Properties getValidationProperties() {
        return new Properties();
    }

    @Provides
    protected ValidatorFactory getValidationFactory(
            Provider<MessageInterpolator> interpolatorProvider) {
        HibernateValidatorConfiguration configure =
            Validation.byProvider(HibernateValidator.class).configure();

        configure.messageInterpolator(interpolatorProvider.get());
        return configure.buildValidatorFactory();
    }

    protected void configureJPA() {
        Configuration jpaConfig = config.strippedSubset(ConfigurationPrefixes.JPA_CONFIG_PREFIX);
        install(new JpaPersistModule("default").properties(jpaConfig.toProperties()));
        bind(JPAInitializer.class).asEagerSingleton();
    }

    private void configureInterceptors() {
        bind(AuthInterceptor.class);
        bind(PageRequestInterceptor.class);
        bind(PinsetterAsyncInterceptor.class);
        bind(VersionPostInterceptor.class);
        bind(LinkHeaderPostInterceptor.class);
        bind(DynamicFilterInterceptor.class);

        bindConstant().annotatedWith(Names.named("PREFIX_APIURL_KEY"))
            .to(ConfigProperties.PREFIX_APIURL);
    }

    private void configurePinsetter() {
        SimpleScope pinsetterJobScope = new SimpleScope();
        bindScope(PinsetterJobScoped.class, pinsetterJobScope);
        bind(SimpleScope.class).annotatedWith(Names.named("PinsetterJobScope")).toInstance(pinsetterJobScope);

        bind(JobFactory.class).to(GuiceJobFactory.class).in(Scopes.SINGLETON);
        bind(JobListener.class).to(PinsetterJobListener.class);
        bind(PinsetterKernel.class);
        bind(CertificateRevocationListTask.class);
        bind(JobCleaner.class);
        bind(ExportCleaner.class);
        bind(UnpauseJob.class);
        bind(SweepBarJob.class);
    }

    private void configureExporter() {
        bind(Exporter.class);
        bind(MetaExporter.class);
        bind(ConsumerTypeExporter.class);
        bind(ConsumerExporter.class);
        bind(RulesExporter.class);
        bind(EntitlementCertExporter.class);
    }

    private void configureAmqp() {
        // for lazy loading:
        bind(AMQPBusPublisher.class).toProvider(AMQPBusPubProvider.class)
                .in(Singleton.class);
    }
}
