package net.trajano.doxdb.ejb;

import javax.annotation.PostConstruct;
import javax.ejb.EJB;
import javax.ejb.LocalBean;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import net.trajano.doxdb.ext.ConfigurationProvider;
import net.trajano.doxdb.schema.DoxPersistence;
import net.trajano.doxdb.schema.DoxType;

/**
 * This will initialize the Dox bean using an EJB that provides the Dox
 * configuration. It will also create the tables based on the configuration if
 * needed.
 *
 * @author Archimedes
 */
@Singleton
@Startup
@LocalBean
public class Initializer {

    private ConfigurationProvider configurationProvider;

    @PersistenceContext
    private EntityManager em;

    /**
     * This will create the tables if needed on initialization. It forces a new
     * transaction to be created in order to prevent issues running in a cluster
     * where two instances of this bean will exist.
     */
    @PostConstruct
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void init() {

        final DoxPersistence persistenceConfig = configurationProvider.getPersistenceConfig();

        for (final DoxType doxConfig : persistenceConfig.getDox()) {
            // TODO TBD
        }

        //        try (final JpaDirectory jpaDirectory = new JpaDirectory(em, LuceneDoxSearchBean.DIRECTORY_NAME)) {
        //            final int releaseCount = jpaDirectory.releaseLocks();
        //            if (releaseCount > 0) {
        //                System.out.println("Index was locked on startup, possible data corruption so re-indexing");
        //                // TODO
        //            }
        //        } catch (final IOException e) {
        //            throw new ExceptionInInitializerError(e);
        //        }

    }

    @EJB
    public void setConfigurationProvider(final ConfigurationProvider configurationProvider) {

        this.configurationProvider = configurationProvider;
    }

}
