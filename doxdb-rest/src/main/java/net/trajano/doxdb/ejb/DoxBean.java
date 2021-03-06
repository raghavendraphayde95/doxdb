package net.trajano.doxdb.ejb;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import javax.annotation.Resource;
import javax.ejb.Asynchronous;
import javax.ejb.EJB;
import javax.ejb.LocalBean;
import javax.ejb.SessionContext;
import javax.ejb.Stateless;
import javax.enterprise.context.Dependent;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.NoResultException;
import javax.persistence.OptimisticLockException;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceException;
import javax.validation.ValidationException;

import org.bson.BsonDocument;

import com.github.fge.jackson.JsonLoader;
import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import com.github.fge.jsonschema.core.report.ProcessingReport;
import com.github.fge.jsonschema.main.JsonSchema;

import net.trajano.doxdb.Dox;
import net.trajano.doxdb.DoxID;
import net.trajano.doxdb.DoxLock;
import net.trajano.doxdb.DoxLookup;
import net.trajano.doxdb.DoxMeta;
import net.trajano.doxdb.DoxTombstone;
import net.trajano.doxdb.DoxUnique;
import net.trajano.doxdb.IndexView;
import net.trajano.doxdb.SearchResult;
import net.trajano.doxdb.ext.CollectionAccessControl;
import net.trajano.doxdb.ext.ConfigurationProvider;
import net.trajano.doxdb.ext.EventHandler;
import net.trajano.doxdb.ext.Indexer;
import net.trajano.doxdb.ext.Migrator;
import net.trajano.doxdb.jsonpath.JsonPath;
import net.trajano.doxdb.schema.CollectionType;
import net.trajano.doxdb.schema.DoxPersistence;
import net.trajano.doxdb.schema.LookupType;
import net.trajano.doxdb.schema.ReadAllType;
import net.trajano.doxdb.schema.SchemaType;

/**
 * Implements the DoxDB persistence operations. An EJB is used to take advantage
 * of transaction management that is provided by EJBs.
 *
 * @author Archimedes Trajano
 */
@Stateless
@Dependent
@LocalBean
public class DoxBean implements
    DoxLocal {

    /**
     * This will create a new JsonObject with the _id and _version fields set.
     * Also any top level values whose key starts with "_" is removed.
     *
     * @param jsonObject
     *            object to decorate
     * @param doxId
     *            Dox ID
     * @param version
     *            optimistic locking version
     * @return decorated JSOB object.
     */
    public static JsonObject decorateWithIdVersion(final JsonObject jsonObject,
        final DoxID doxId,
        final int version) {

        final JsonObjectBuilder b = Json.createObjectBuilder();
        b.add("_id", doxId.toString());
        b.add("_version", version);
        for (final String key : jsonObject.keySet()) {
            if (!key.startsWith("_")) {
                b.add(key, jsonObject.get(key));
            }
        }
        return b.build();
    }

    /**
     * Extracts the extra info. Extra info is any property that starts with
     * {@code _} except for {@code _id} and {@code _version}.
     *
     * @param jsonObject
     * @return {@link JsonObject} containing only extra data.
     */
    private static JsonObject getExtra(final JsonObject jsonObject) {

        final JsonObjectBuilder b = Json.createObjectBuilder();
        for (final String key : jsonObject.keySet()) {
            if (key.startsWith("_") && !"_id".equals(key) && !"_version".equals(key) && jsonObject.getValueType() == JsonValue.ValueType.STRING) {
                b.add(key, jsonObject.get(key));
            }
        }
        return b.build();
    }

    /**
     * This will create a new JsonObject with reserved properties removed.
     * Reserved properties start with "_" including "_id" and "_version".
     *
     * @param jsonObject
     *            object to decorate
     * @param doxId
     *            Dox ID
     * @param version
     *            optimistic locking version
     * @return decorated JSOB object.
     */
    public static JsonObject sanitize(final JsonObject jsonObject) {

        final JsonObjectBuilder b = Json.createObjectBuilder();
        for (final String key : jsonObject.keySet()) {
            if (!key.startsWith("_")) {
                b.add(key, jsonObject.get(key));
            }
        }
        return b.build();
    }

    private CollectionAccessControl collectionAccessControl;

    private ConfigurationProvider configurationProvider;

    /**
     * Session context. It is injected here rather than
     * {@link #setSessionContext(SessionContext)} as the WebSphere tools flag
     * that using the setter is not valid incorrectly, but will still work in
     * this fasion as well.
     */
    @Resource
    private SessionContext ctx;

    private DoxSearch doxSearchBean;

    private EntityManager em;

    private EventHandler eventHandler;

    private Indexer indexer;

    private Migrator migrator;

    @Override
    public SearchResult advancedSearch(final String index,
        final JsonObject query) {

        return doxSearchBean.advancedSearch(index, query);
    }

    @Override
    public SearchResult advancedSearch(final String index,
        final String schemaName,
        final JsonObject query) {

        return doxSearchBean.advancedSearch(index, schemaName, query);
    }

    @Override
    public DoxMeta create(final String collectionName,
        final JsonObject unsanitizedContent) {

        final Date ts = new Date();
        final CollectionType config = configurationProvider.getCollection(collectionName);
        final SchemaType schema = configurationProvider.getCollectionSchema(collectionName);

        final JsonObject extra = getExtra(unsanitizedContent);
        final JsonObject content = sanitize(unsanitizedContent);
        validate(schema, content);

        final DoxID doxId = DoxID.generate();

        final String inputJson = content.toString();
        final byte[] accessKey = collectionAccessControl.buildAccessKey(config.getName(), inputJson, ctx.getCallerPrincipal().getName());

        final Dox entity = new Dox();
        entity.setDoxId(doxId);
        entity.setContent(inputJson);
        entity.setCreatedBy(ctx.getCallerPrincipal());
        entity.setCreatedOn(ts);
        entity.setLastUpdatedBy(ctx.getCallerPrincipal());
        entity.setLastUpdatedOn(ts);
        entity.setCollectionName(config.getName());
        entity.setCollectionSchemaVersion(schema.getVersion());
        entity.setAccessKey(accessKey);
        entity.setVersion(1);

        em.persist(entity);

        for (final LookupType unique : schema.getUnique()) {
            final String lookupKey = JsonPath.compile(unique.getPath()).read(inputJson);
            final DoxUnique doxUnique = new DoxUnique();
            doxUnique.setCollectionName(config.getName());
            doxUnique.setDox(entity);
            doxUnique.setLookupName(unique.getName());
            doxUnique.setLookupKey(lookupKey);
            em.persist(doxUnique);
        }
        for (final LookupType unique : schema.getLookup()) {
            final String lookupKey = JsonPath.compile(unique.getPath()).read(inputJson);
            final DoxLookup doxLookup = new DoxLookup();
            doxLookup.setCollectionName(config.getName());
            doxLookup.setDox(entity);
            doxLookup.setLookupName(unique.getName());
            doxLookup.setLookupKey(lookupKey);
            em.persist(doxLookup);
        }

        final IndexView[] indexViews = indexer.buildIndexViews(config.getName(), inputJson);
        for (final IndexView indexView : indexViews) {
            indexView.setCollection(config.getName());
            indexView.setDoxID(doxId);
        }
        if (indexViews.length > 0) {
            doxSearchBean.addToIndex(indexViews);
        }
        final DoxMeta meta = new DoxMeta();
        meta.setCollectionName(collectionName);
        meta.setAccessKey(accessKey);
        meta.setLastUpdatedBy(ctx.getCallerPrincipal());
        meta.setLastUpdatedOn(ts);
        meta.setVersion(1);
        meta.setDoxId(doxId);

        meta.setContentJson(content, doxId, 1);

        eventHandler.onRecordCreate(meta, content.toString(), extra);
        return meta;
    }

    @Override
    public boolean delete(final String collectionName,
        final DoxID doxid,
        final int version,
        final JsonObject extraJson) {

        final Date ts = new Date();
        final CollectionType config = configurationProvider.getCollection(collectionName);
        final JsonObject extra = getExtra(extraJson);
        final DoxMeta meta = readMetaAndLock(config.getName(), doxid, version);

        meta.getAccessKey();
        // TODO check the security.

        final Dox toBeDeleted = em.find(Dox.class, meta.getId());
        if (toBeDeleted == null) {
            return false;
        }
        em.createNamedQuery(DoxUnique.REMOVE_UNIQUE_FOR_DOX).setParameter("dox", toBeDeleted).executeUpdate();
        em.createNamedQuery(DoxLookup.REMOVE_LOOKUP_FOR_DOX).setParameter("dox", toBeDeleted).executeUpdate();

        final BsonDocument contentBson = toBeDeleted.getContent();
        final DoxTombstone tombstone = toBeDeleted.buildTombstone(ctx.getCallerPrincipal(), ts);
        em.persist(tombstone);
        em.remove(toBeDeleted);

        final SchemaType schema = configurationProvider.getCollectionSchema(collectionName);

        String contentJson = contentBson.toJson();
        if (meta.getCollectionSchemaVersion() != schema.getVersion()) {
            contentJson = migrator.migrate(collectionName, meta.getCollectionSchemaVersion(), schema.getVersion(), contentJson);
        }

        doxSearchBean.removeFromIndex(config.getName(), doxid);
        eventHandler.onRecordDelete(meta, contentJson, extra);
        return true;

    }

    /**
     * Performs the update operation.
     */
    private DoxMeta doUpdate(final String collectionName,
        final DoxID doxId,
        final JsonObject unsanitizedContent,
        final int version) {

        final Timestamp ts = new Timestamp(System.currentTimeMillis());
        final CollectionType config = configurationProvider.getCollection(collectionName);
        final SchemaType schema = configurationProvider.getCollectionSchema(collectionName);

        final JsonObject extra = getExtra(unsanitizedContent);
        final JsonObject content = sanitize(unsanitizedContent);
        final String inputJson = content.toString();
        validate(schema, inputJson);

        final DoxMeta meta = readMetaAndLock(config.getName(), doxId, version);
        meta.incrementVersion();

        meta.getAccessKey();
        // TODO check the security.

        final IndexView[] indexViews = indexer.buildIndexViews(config.getName(), inputJson);

        final byte[] accessKey = collectionAccessControl.buildAccessKey(config.getName(), inputJson, ctx.getCallerPrincipal().getName());

        final Dox e = em.find(Dox.class, meta.getId());
        e.setLastUpdatedBy(ctx.getCallerPrincipal());
        e.setLastUpdatedOn(ts);
        e.setContent(content);
        e.setAccessKey(accessKey);
        em.persist(e);

        for (final LookupType unique : schema.getUnique()) {
            final String lookupKey = JsonPath.compile(unique.getPath()).read(inputJson);
            em.createNamedQuery(DoxUnique.UPDATE_UNIQUE_FOR_DOX).setParameter("dox", e).setParameter(DoxUnique.LOOKUP_KEY, lookupKey).executeUpdate();
        }
        for (final LookupType lookup : schema.getLookup()) {
            final String lookupKey = JsonPath.compile(lookup.getPath()).read(inputJson);
            em.createNamedQuery(DoxLookup.UPDATE_LOOKUP_FOR_DOX).setParameter("dox", e).setParameter(DoxUnique.LOOKUP_KEY, lookupKey).executeUpdate();
        }

        for (final IndexView indexView : indexViews) {
            indexView.setCollection(config.getName());
            indexView.setDoxID(doxId);
        }
        doxSearchBean.addToIndex(indexViews);

        meta.setContentJson(content, doxId, e.getVersion());
        eventHandler.onRecordUpdate(meta, e.getJsonContent(), extra);
        return meta;

    }

    @Override
    public DoxPersistence getConfiguration() {

        return configurationProvider.getPersistenceConfig();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InputStream getSchema(final String path) {

        return Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/schema/" + path);
    }

    @Override
    public boolean isLocked(final String collectionName,
        final DoxID doxId) {

        if (!configurationProvider.getCollection(collectionName).isLockable()) {
            throw new PersistenceException(collectionName + " is not lockable");
        }

        try {
            em.createNamedQuery(DoxLock.READ_LOCK_BY_COLLECTION_NAME_DOX_ID)
                .setParameter(DoxLock.COLLECTION_NAME, collectionName)
                .setParameter(DoxLock.DOXID, doxId.toString())
                .getSingleResult();
            return true;
        } catch (final NoResultException e) {
            return false;
        }

    }

    @Override
    public int lock(final String collectionName,
        final DoxID doxId) {

        if (!configurationProvider.getCollection(collectionName).isLockable()) {
            throw new PersistenceException(collectionName + " is not lockable");
        }

        final Date ts = new Date();
        final DoxLock lock = new DoxLock();
        lock.generateLockID();
        lock.setLockedDox(em.createNamedQuery(Dox.READ_BY_COLLECTION_NAME_DOX_ID, Dox.class)
            .setParameter(DoxLock.COLLECTION_NAME, collectionName)
            .setParameter(DoxLock.DOXID, doxId.toString())
            .getSingleResult());
        lock.setLockedBy(ctx.getCallerPrincipal());
        lock.setLockedOn(ts);

        em.persist(lock);
        return lock.getLockId();
    }

    @Override
    public void noop() {

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DoxMeta read(final String collectionName,
        final DoxID doxid) {

        final CollectionType config = configurationProvider.getCollection(collectionName);
        final SchemaType schema = configurationProvider.getCollectionSchema(collectionName);

        final DoxMeta meta;
        try {
            meta = em.createNamedQuery(Dox.READ_META_BY_COLLECTION_NAME_DOX_ID, DoxMeta.class).setParameter("doxId", doxid.toString()).setParameter("collectionName", config.getName()).getSingleResult();
        } catch (final NoResultException e) {
            return null;
        }
        meta.getAccessKey();
        // TODO check the security.

        if (meta.getCollectionSchemaVersion() != schema.getVersion()) {
            final Dox e = em.find(Dox.class, meta.getId(), LockModeType.OPTIMISTIC_FORCE_INCREMENT);
            final String contentJson = migrator.migrate(collectionName, e.getCollectionSchemaVersion(), schema.getVersion(), e.getJsonContent());
            meta.setCollectionName(collectionName);
            meta.setCollectionSchemaVersion(schema.getVersion());
            e.setCollectionSchemaVersion(schema.getVersion());
            e.setContent(contentJson);
            em.persist(e);
            em.flush();
            em.refresh(e);
            final JsonObject content = e.getJsonObject();
            meta.setContentJson(content, meta.getDoxId(), e.getVersion());
        } else {
            final Dox e = em.find(Dox.class, meta.getId(), LockModeType.OPTIMISTIC);
            final JsonObject content = e.getJsonObject();
            meta.setContentJson(content, meta.getDoxId(), meta.getVersion());
        }
        eventHandler.onRecordRead(ctx.getCallerPrincipal(), collectionName, doxid, meta.getContentJson());
        return meta;

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String readAll(final String collectionName) {

        final CollectionType config = configurationProvider.getCollection(collectionName);
        if (config.getReadAll() == ReadAllType.FILE) {
            try {
                return readAllToFile(config.getName());
            } catch (final IOException e) {
                throw new PersistenceException(e);
            }
        } else if (config.getReadAll() == ReadAllType.MEMORY) {
            return readAllToString(config.getName());
        } else {
            throw new PersistenceException("Not supported");
        }

    }

    /**
     * Reads all records in a collection and writes it to a file.
     *
     * @param collectionName
     * @return
     * @throws IOException
     */
    private String readAllToFile(final String collectionName) throws IOException {

        final SchemaType schema = configurationProvider.getCollectionSchema(collectionName);

        final File f = File.createTempFile("doxdb", collectionName);

        try (final Writer os = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(f)), "UTF-8")) {
            os.write('[');

            final List<Dox> results = em.createNamedQuery(Dox.READ_ALL_BY_COLLECTION_NAME, Dox.class).setParameter("collectionName", collectionName).getResultList();
            final Iterator<Dox> i = results.iterator();
            while (i.hasNext()) {

                final Dox result = i.next();
                final boolean last = !i.hasNext();
                result.getAccessKey();
                // TODO check security
                if (result.getCollectionSchemaVersion() != schema.getVersion()) {
                    migrator.migrate(collectionName, result.getCollectionSchemaVersion(), schema.getVersion(), result.getJsonContent());
                    // queue migrate later?
                } else {
                    os.write(decorateWithIdVersion(result.getJsonObject(), result.getDoxId(), result.getVersion()).toString());
                    if (!last) {
                        os.write(',');
                    }
                }

            }
            os.write(']');
        }
        return f.getCanonicalPath();

    }

    private String readAllToString(final String collectionName) {

        final SchemaType schema = configurationProvider.getCollectionSchema(collectionName);

        final StringBuilder b = new StringBuilder("[");

        final List<Dox> results = em.createNamedQuery(Dox.READ_ALL_BY_COLLECTION_NAME, Dox.class).setParameter(Dox.COLLECTION_NAME, collectionName).getResultList();
        for (final Dox result : results) {

            result.getAccessKey();
            // TODO check security
            if (result.getCollectionSchemaVersion() != schema.getVersion()) {
                migrator.migrate(collectionName, result.getCollectionSchemaVersion(), schema.getVersion(), result.getJsonContent());
                // queue migrate later?
            } else {
                b.append(decorateWithIdVersion(result.getJsonObject(), result.getDoxId(), result.getVersion()).toString());
                b.append(',');
            }

        }
        if (b.length() > 1) {
            b.replace(b.length() - 1, b.length(), "]");
        } else {
            b.append(']');
        }
        return b.toString();

    }

    @Override
    public JsonArray readByLookup(final String collectionName,
        final String lookupName,
        final String lookupKey) {

        final List<Dox> results = em.createNamedQuery(DoxLookup.LOOKUP, Dox.class)
            .setParameter(DoxLookup.COLLECTION_NAME, collectionName)
            .setParameter(DoxLookup.LOOKUP_NAME, lookupName)
            .setParameter(DoxLookup.LOOKUP_KEY, lookupKey).getResultList();

        final SchemaType schema = configurationProvider.getCollectionSchema(collectionName);

        final JsonArrayBuilder b = Json.createArrayBuilder();

        for (final Dox result : results) {

            result.getAccessKey();
            // TODO check security
            if (result.getCollectionSchemaVersion() != schema.getVersion()) {
                migrator.migrate(collectionName, result.getCollectionSchemaVersion(), schema.getVersion(), result.getJsonContent());
                // queue migrate later?
            } else {
                b.add(decorateWithIdVersion(result.getJsonObject(), result.getDoxId(), result.getVersion()));
                eventHandler.onRecordRead(ctx.getCallerPrincipal(), collectionName, result.getDoxId(), result.getJsonContent());
            }

        }
        return b.build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DoxMeta readByUniqueLookup(final String collectionName,
        final String lookupName,
        final String lookupKey) {

        final Dox dox = (Dox) em.createNamedQuery(DoxUnique.UNIQUE_LOOKUP)
            .setParameter(DoxUnique.COLLECTION_NAME, collectionName)
            .setParameter(DoxUnique.LOOKUP_NAME, lookupName)
            .setParameter(DoxUnique.LOOKUP_KEY, lookupKey).getSingleResult();
        return read(collectionName, dox.getDoxId());
    }

    private DoxMeta readMetaAndLock(
        final String schemaName,
        final DoxID doxid,
        final int version) {

        try {
            return em.createNamedQuery(Dox.READ_FOR_UPDATE_META_BY_SCHEMA_NAME_DOX_ID_VERSION, DoxMeta.class).setParameter("doxId", doxid.toString()).setParameter("collectionName", schemaName).setParameter("version", version).getSingleResult();

        } catch (final NoResultException e) {
            throw new OptimisticLockException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Asynchronous
    public void reindex() {

        doxSearchBean.reset();
        // TODO this will do everything in one transaction which can kill the database.  What could be done is the
        // reindexing can be done in chunks and let an MDB do the process
        for (final CollectionType config : configurationProvider.getPersistenceConfig().getCollection()) {

            em.createNamedQuery(DoxUnique.REMOVE_ALL)
                .executeUpdate();

            em.createNamedQuery(DoxLookup.REMOVE_ALL)
                .executeUpdate();

            final SchemaType schemaType = config.getSchema().get(config.getSchema().size() - 1);

            final List<IndexView> indexViews = new LinkedList<>();
            for (final Dox e : em.createNamedQuery(Dox.READ_ALL_BY_COLLECTION_NAME, Dox.class).setParameter(Dox.COLLECTION_NAME, config.getName()).getResultList()) {

                for (final DoxUnique doxUnique : DoxUnique.fromDox(e, schemaType)) {
                    em.persist(doxUnique);
                }
                for (final DoxLookup doxLookup : DoxLookup.fromDox(e, schemaType)) {
                    em.persist(doxLookup);
                }
                // TODO later
                //                rs.updateBytes(3, collectionAccessControl.buildAccessKey(config.getName(), json, new DoxPrincipal(rs.getString(4))));
                final IndexView[] indexViewBuilt = indexer.buildIndexViews(config.getName(), e.getJsonContent());
                for (final IndexView indexView : indexViewBuilt) {
                    indexView.setCollection(e.getCollectionName());
                    indexView.setDoxID(e.getDoxId());
                    indexViews.add(indexView);
                }

            }
            doxSearchBean.addToIndex(indexViews.toArray(new IndexView[0]));

        }

    }

    @Override
    public SearchResult search(final String index,
        final String queryString,
        final int limit) {

        return doxSearchBean.search(index, queryString, limit, null);
    }

    @Override
    public SearchResult search(final String index,
        final String queryString,
        final int limit,
        final Integer fromDoc) {

        return doxSearchBean.search(index, queryString, limit, fromDoc);
    }

    @Override
    public SearchResult searchWithCollectionName(final String index,
        final String schemaName,
        final String queryString,
        final int limit,
        final Integer fromDoc) {

        return doxSearchBean.searchWithSchemaName(index, schemaName, queryString, limit, fromDoc);
    }

    @EJB
    public void setCollectionAccessControl(final CollectionAccessControl collectionAccessControl) {

        this.collectionAccessControl = collectionAccessControl;
    }

    @EJB
    public void setConfigurationProvider(final ConfigurationProvider configurationProvider) {

        this.configurationProvider = configurationProvider;
    }

    @EJB
    public void setDoxSearchBean(final DoxSearch doxSearchBean) {

        this.doxSearchBean = doxSearchBean;
    }

    /**
     * Injects the {@link EntityManager}.
     *
     * @param em
     *            entity manager
     */
    @PersistenceContext
    public void setEntityManager(final EntityManager em) {

        this.em = em;
    }

    @EJB
    public void setEventHandler(final EventHandler eventHandler) {

        this.eventHandler = eventHandler;
    }

    @EJB
    public void setIndexer(final Indexer indexer) {

        this.indexer = indexer;
    }

    @EJB
    public void setMigrator(final Migrator migrator) {

        this.migrator = migrator;
    }

    public void setSessionContext(final SessionContext ctx) {

        this.ctx = ctx;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unlock(final String collectionName,
        final DoxID doxId,
        final int lockId) {

        if (!configurationProvider.getCollection(collectionName).isLockable()) {
            throw new PersistenceException(collectionName + " is not lockable");
        }

        em.createNamedQuery(DoxLock.REMOVE_LOCK_BY_COLLECTION_NAME_DOX_ID_LOCK_ID)
            .setParameter(DoxLock.COLLECTION_NAME, collectionName)
            .setParameter(DoxLock.DOXID, doxId.toString())
            .setParameter(DoxLock.LOCKID, lockId).executeUpdate();

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DoxMeta update(final String collectionName,
        final DoxID doxId,
        final JsonObject contents,
        final int version) {

        if (configurationProvider.getCollection(collectionName).isLockable()) {
            throw new PersistenceException("The lockId must be specified for updating " + collectionName);
        }
        return doUpdate(collectionName, doxId, contents, version);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DoxMeta update(final String collectionName,
        final DoxID doxId,
        final JsonObject contents,
        final int version,
        final int lockId) {

        if (!configurationProvider.getCollection(collectionName).isLockable()) {
            throw new PersistenceException(collectionName + " is not lockable");
        }
        verifyLockedBy(collectionName, doxId, lockId);
        return doUpdate(collectionName, doxId, contents, version);
    }

    private void validate(final SchemaType schema,
        final JsonObject content) {

        validate(schema, content.toString());

    }

    /**
     * Performs JSON validation using a schema
     *
     * @param schema
     *            schema
     * @param json
     *            json to validate.
     */
    private void validate(final SchemaType schema,
        final String json) {

        try {

            final JsonSchema jsonSchema = configurationProvider.getContentSchema(schema.getLocation());

            final ProcessingReport validate = jsonSchema.validate(JsonLoader.fromString(json));
            if (!validate.isSuccess()) {
                throw new ValidationException(validate.toString());
            }
        } catch (ProcessingException
            | IOException e) {
            throw new PersistenceException(e);
        }
    }

    /**
     * Check if a record is locked by the given lock ID.
     *
     * @param collectionName
     *            collection name
     * @param doxId
     *            Dox ID
     * @param lockId
     *            lock ID
     */
    private void verifyLockedBy(final String collectionName,
        final DoxID doxId,
        final int lockId) {

        em.createNamedQuery(DoxLock.READ_LOCK_BY_COLLECTION_NAME_DOX_ID_LOCK_ID)
            .setParameter(DoxLock.COLLECTION_NAME, collectionName)
            .setParameter(DoxLock.DOXID, doxId.toString())
            .setParameter(DoxLock.LOCKID, lockId).getSingleResult();
    }

}
