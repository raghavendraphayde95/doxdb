package net.trajano.doxdb.sample.test;

import java.io.StringReader;
import java.sql.Connection;

import javax.json.Json;
import javax.json.JsonObject;

import org.junit.Assert;
import org.junit.Test;

import net.trajano.doxdb.DoxID;
import net.trajano.doxdb.jdbc.DoxPrincipal;
import net.trajano.doxdb.sample.SampleJsonBean;

public class SampleJsonEntityTest extends AbstractEntityTest {

    @Test
    public void testCrud() throws Exception {

        tx.begin();

        Connection connection = em.unwrap(Connection.class);
        Assert.assertNotNull(connection);

        SampleJsonBean bean = new SampleJsonBean(connection);

        String inputJson = "{\"doc\":\"abc\"}";
        JsonObject o = Json.createReader(new StringReader(inputJson))
                .readObject();
        DoxID id = bean.create(o, new DoxPrincipal("PRINCE"));
        Assert.assertEquals(inputJson, bean.readContent(id)
                .toString());
        tx.commit();
    }
}
