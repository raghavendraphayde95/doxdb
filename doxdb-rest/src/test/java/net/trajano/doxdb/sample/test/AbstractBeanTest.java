package net.trajano.doxdb.sample.test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.logging.LogManager;

import javax.ejb.SessionContext;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.Persistence;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;

import net.trajano.doxdb.IndexView;
import net.trajano.doxdb.ejb.DoxBean;
import net.trajano.doxdb.ejb.DoxSearch;
import net.trajano.doxdb.ext.CollectionAccessControl;
import net.trajano.doxdb.ext.ConfigurationProvider;
import net.trajano.doxdb.ext.DefaultEventHandler;
import net.trajano.doxdb.ext.Indexer;
import net.trajano.doxdb.ext.XmlConfigurationProvider;
import net.trajano.doxdb.internal.DoxPrincipal;

public class AbstractBeanTest {

    @BeforeClass
    public static void setupLogger() throws Exception {

        LogManager.getLogManager()
            .readConfiguration(Resources.getResource("logging.properties")
                .openStream());
    }

    protected DoxBean bean;

    protected DoxSearch doxSearchBean;

    protected EntityManager em;

    protected EntityManagerFactory emf;

    @Rule
    public TemporaryFolder testFolder = new TemporaryFolder();

    protected EntityTransaction tx;

    @Before
    public void setUp() throws IOException {

        emf = Persistence.createEntityManagerFactory("default", ImmutableMap.builder()
            .put("javax.persistence.jdbc.driver", "org.apache.derby.jdbc.EmbeddedDriver")
            .put("javax.persistence.schema-generation.database.action", "create")
            .put("javax.persistence.jdbc.url", "jdbc:h2:file:" + testFolder.newFile().getAbsolutePath())
            //            .put("eclipselink.logging.logger", "JavaLogger")
            //            .put("eclipselink.logging.level.sql", "fine")
            //            .put("eclipselink.logging.parameters", "true")
            .build());
        em = emf.createEntityManager();
        tx = em.getTransaction();

        final ConfigurationProvider configurationProvider = new XmlConfigurationProvider("abstractbeantest-dox.xml");

        final SessionContext sessionContextMock = mock(SessionContext.class);
        when(sessionContextMock.getCallerPrincipal()).thenReturn(new DoxPrincipal("ANONYMOUS"));

        bean = new DoxBean();
        doxSearchBean = Mockito.mock(DoxSearch.class);

        bean.setEntityManager(em);
        bean.setSessionContext(sessionContextMock);
        bean.setIndexer(new Indexer() {

            @Override
            public IndexView[] buildIndexViews(final String collection,
                final String json) {

                return new IndexView[0];
            }
        });
        bean.setCollectionAccessControl(new CollectionAccessControl() {

            @Override
            public byte[] buildAccessKey(final String collection,
                final String json,
                final String principal) {

                return null;
            }
        });
        bean.setEventHandler(new DefaultEventHandler());
        bean.setConfigurationProvider(configurationProvider);
        bean.setDoxSearchBean(doxSearchBean);

    }

    @After
    public void tearDownObjects() {

        em.close();
        emf.close();
    }

}
