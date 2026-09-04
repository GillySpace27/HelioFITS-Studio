package org.helioviewer.jhv.database;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.zip.GZIPInputStream;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.event.JHVEvent;
import org.helioviewer.jhv.event.SWEK;
import org.helioviewer.jhv.event.SWEKCatalog;
import org.helioviewer.jhv.event.SWEKGroup;
import org.helioviewer.jhv.event.SWEKHandler;
import org.helioviewer.jhv.event.SWEKSupplier;
import org.helioviewer.jhv.io.JSONUtils;
import org.helioviewer.jhv.thread.AppThread;
import org.helioviewer.jhv.thread.SingleExecutor;
import org.helioviewer.jhv.time.Interval;
import org.helioviewer.jhv.time.RequestCache;

public class EventDatabase {

    private static final SingleExecutor executor = new SingleExecutor(new AppThread.NamedThreadFactory("EventDatabase"));

    private static final long ONEWEEK = 1000 * 60 * 60 * 24 * 7;
    public static int config_hash;

    private static final String INSERT_EVENT = "INSERT INTO events(uid) VALUES(?)";
    private static final String INSERT_FULL_EVENT = "INSERT INTO events(type_id, uid, start, end, archiv, data) VALUES(?,?,?,?,?,?)";
    private static final String SELECT_EVENT_TYPE = "SELECT id FROM event_type WHERE name=? AND supplier=?";
    private static final String INSERT_EVENT_TYPE = "INSERT INTO event_type(name, supplier) VALUES(?,?)";
    private static final String INSERT_LINK = "INSERT INTO event_link(left_id, right_id) VALUES(?,?)";
    private static final String SELECT_EVENT_ID_FROM_UID = "SELECT id FROM events WHERE uid=?";
    private static final String SELECT_LAST_INSERT = "SELECT last_insert_rowid()";
    private static final String UPDATE_EVENT = "UPDATE events SET type_id=?, uid=?, start=?, end=?, archiv=?, data=? WHERE id=?";
    private static final String DELETE_DATERANGES = "DELETE FROM date_range WHERE type_id=?";
    private static final String INSERT_DATERANGE = "INSERT INTO date_range(type_id,  start, end) VALUES(?,?,?)";
    private static final String SELECT_DATERANGE = "SELECT start, end FROM date_range where type_id=? order by start, end ";
    private static final String SELECT_LAST_EVENT = "SELECT end FROM events WHERE type_id=? order by end DESC LIMIT 1";
    private static final String SELECT_ASSOCIATIONS =
            "SELECT event_link.left_id, event_link.right_id FROM events JOIN event_link ON events.id=event_link.left_id " +
                    "WHERE events.type_id=? AND events.start<=? AND events.end>=? UNION " +
                    "SELECT event_link.left_id, event_link.right_id FROM events JOIN event_link ON events.id=event_link.right_id " +
                    "WHERE events.type_id=? AND events.start<=? AND events.end>=?";
    private static final HashMap<String, PreparedStatement> statements = new HashMap<>();
    private static final HashMap<SWEKSupplier, RequestCache> storedIntervals = new HashMap<>();

    private static PreparedStatement getPreparedStatement(String statement) throws Exception {
        PreparedStatement pstat = statements.get(statement);
        if (pstat == null) {
            pstat = EventDatabaseThread.getConnection().prepareStatement(statement);
            pstat.setQueryTimeout(30);
            statements.put(statement, pstat);
        }
        return pstat;
    }

    private static int findOrInsertEventTypeId(SWEKSupplier supplier) throws Exception {
        int typeId = findEventTypeId(supplier);
        if (typeId == -1) {
            insertEventTypeIfNotExist(supplier);
            typeId = findEventTypeId(supplier);
        }
        return typeId;
    }

    private static int findEventTypeId(SWEKSupplier supplier) throws Exception {
        int typeId = -1;
        PreparedStatement pstatement = getPreparedStatement(SELECT_EVENT_TYPE);
        pstatement.setString(1, supplier.group().getName());
        pstatement.setString(2, SWEKCatalog.key(supplier));

        try (ResultSet rs = pstatement.executeQuery()) {
            if (rs.next()) {
                typeId = rs.getInt(1);
            }
        }
        return typeId;
    }

    private static void insertEventTypeIfNotExist(SWEKSupplier eventType) throws Exception {
        PreparedStatement pstatement = getPreparedStatement(INSERT_EVENT_TYPE);
        pstatement.setString(1, eventType.group().getName());
        pstatement.setString(2, SWEKCatalog.key(eventType));
        pstatement.executeUpdate();

        StringBuilder createtbl = new StringBuilder("CREATE TABLE ").append(eventType.dbName())
                .append(" (event_id INTEGER PRIMARY KEY ON CONFLICT REPLACE");
        SWEKCatalog.databaseFields(eventType).forEach((key, value) ->
                createtbl.append(',').append(key).append(' ').append(value));
        createtbl.append(", FOREIGN KEY(event_id) REFERENCES events(id))");

        Connection connection = pstatement.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            statement.executeUpdate(createtbl.toString());
            createEventTableIndexes(statement, eventType);
        }
        connection.commit();
    }

    private static void createEventTableIndexes(Statement statement, SWEKSupplier eventType) throws Exception {
        String tableName = eventType.dbName();
        for (String field : SWEKCatalog.databaseFields(eventType).keySet())
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS " + tableName + '_' + field + " ON " + tableName + " (" + field + ")");
    }

    private static int findOrInsertEventId(String uid) throws Exception {
        int id = findEventId(uid);
        if (id == -1) {
            insertVoidEvent(uid);
            id = findEventId(uid);
        }
        return id;
    }

    private static int findEventId(String uid) throws Exception {
        int id = -1;
        PreparedStatement pstatement = getPreparedStatement(SELECT_EVENT_ID_FROM_UID);
        pstatement.setString(1, uid);

        try (ResultSet rs = pstatement.executeQuery()) {
            if (rs.next()) {
                id = rs.getInt(1);
            }
        }
        return id;
    }

    private static void insertVoidEvent(String uid) throws Exception {
        PreparedStatement pstatement = getPreparedStatement(INSERT_EVENT);
        pstatement.setString(1, uid);
        pstatement.executeUpdate();
    }

    private static void insertAssociation(PreparedStatement statement, int id0, int id1) throws SQLException {
        if (id0 == id1)
            return;

        statement.setInt(1, Math.min(id0, id1));
        statement.setInt(2, Math.max(id0, id1));
        statement.executeUpdate();
    }

    public static int storeAssociations(List<JHVEvent.LinkRef> links) {
        try {
            return executor.invokeAndWait(new StoreAssociations(links));
        } catch (Exception e) {
            Log.error(e);
        }
        return -1;
    }

    private record StoreAssociations(List<JHVEvent.LinkRef> links) implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            int errorcode = 0;

            PreparedStatement pstatement = getPreparedStatement(INSERT_LINK);

            for (JHVEvent.LinkRef link : links) {
                int id0 = findOrInsertEventId(link.firstUid());
                int id1 = findOrInsertEventId(link.secondUid());
                if (id0 != -1 && id1 != -1) {
                    insertAssociation(pstatement, id0, id1);
                } else {
                    errorcode = -1;
                    Log.error("Could not add association to database");
                    break;
                }
            }
            pstatement.getConnection().commit();
            return errorcode;
        }
    }

    private static void bindRemoteParameter(PreparedStatement statement, int index, SWEKHandler.RemoteParameter parameter) throws SQLException {
        switch (parameter.value()) {
            case Integer i -> statement.setInt(index, i);
            case String s -> statement.setString(index, s);
            case Double d -> statement.setDouble(index, d);
            default -> throw new IllegalArgumentException("Unsupported remote parameter value: " + parameter.value());
        }
    }

    public static void storeEvents(List<SWEKHandler.RemoteEvent> remoteEvents, SWEKSupplier supplier) {
        try {
            executor.invokeAndWait(new StoreEvents(remoteEvents, supplier));
        } catch (Exception e) {
            Log.error(e);
        }
    }

    private record StoreEvents(List<SWEKHandler.RemoteEvent> remoteEvents,
                               SWEKSupplier supplier) implements Callable<Void> {
        @Override
        public Void call() throws Exception {
            int[] eventIds = new int[remoteEvents.size()];
            Arrays.fill(eventIds, -1);
            int typeId = findOrInsertEventTypeId(supplier);

            PreparedStatement insertFullEvent = getPreparedStatement(INSERT_FULL_EVENT);
            PreparedStatement selectLastInsert = getPreparedStatement(SELECT_LAST_INSERT);
            PreparedStatement updateEvent = getPreparedStatement(UPDATE_EVENT);

            for (int i = 0; i < remoteEvents.size(); i++) {
                SWEKHandler.RemoteEvent event2db = remoteEvents.get(i);
                int generatedKey = -1;
                if (typeId != -1) {
                    generatedKey = findEventId(event2db.uid());

                    if (generatedKey == -1) {
                        insertFullEvent.setInt(1, typeId);
                        insertFullEvent.setString(2, event2db.uid());
                        insertFullEvent.setLong(3, event2db.start());
                        insertFullEvent.setLong(4, event2db.end());
                        insertFullEvent.setLong(5, event2db.archiv());
                        insertFullEvent.setBinaryStream(6, new ByteArrayInputStream(event2db.compressedJson()), event2db.compressedJson().length);
                        insertFullEvent.executeUpdate();

                        try (ResultSet rs = selectLastInsert.executeQuery()) {
                            if (rs.next()) {
                                generatedKey = rs.getInt(1);
                            }
                        }
                    } else {
                        updateEvent.setInt(1, typeId);
                        updateEvent.setString(2, event2db.uid());
                        updateEvent.setLong(3, event2db.start());
                        updateEvent.setLong(4, event2db.end());
                        updateEvent.setLong(5, event2db.archiv());
                        updateEvent.setBinaryStream(6, new ByteArrayInputStream(event2db.compressedJson()), event2db.compressedJson().length);
                        updateEvent.setInt(7, generatedKey);
                        updateEvent.executeUpdate();
                    }
                    {
                        StringBuilder fieldString = new StringBuilder();
                        StringBuilder varString = new StringBuilder();
                        for (SWEKHandler.RemoteParameter p : event2db.paramList()) {
                            fieldString.append(',').append(p.name());
                            varString.append(",?");
                        }
                        String full_statement = "INSERT INTO " + supplier.dbName() + "(event_id" + fieldString + ") VALUES(?" + varString + ')';
                        PreparedStatement pstatement = getPreparedStatement(full_statement);
                        pstatement.setInt(1, generatedKey);

                        int index = 2;
                        for (SWEKHandler.RemoteParameter p : event2db.paramList()) {
                            bindRemoteParameter(pstatement, index, p);
                            index++;
                        }
                        pstatement.executeUpdate();
                    }
                } else {
                    Log.error("Failed to insert event");
                }
                eventIds[i] = generatedKey;
            }
            insertFullEvent.getConnection().commit();
            storeRelatedEventLinks(eventIds, supplier);
            return null;
        }
    }

    private static void storeRelatedEventLinks(int[] eventIds, SWEKSupplier type) throws Exception {
        String table = type.dbName();
        SWEKGroup group = type.group();
        Connection connection = EventDatabaseThread.getConnection();
        for (SWEK.RelatedEvents relation : SWEKCatalog.getRelatedEvents()) {
            if (relation.group() != group || relation.relatedWith() != group)
                continue;

            for (SWEK.RelatedOn relatedOn : relation.relatedOnList()) {
                String sql = "INSERT INTO event_link(left_id, right_id) " +
                        "SELECT DISTINCT min(a.event_id, b.event_id), max(a.event_id, b.event_id) " +
                        "FROM " + table + " AS a JOIN " + table + " AS b ON a." + relatedOn.parameterFrom() + "=b." + relatedOn.parameterWith() + ' ' +
                        "WHERE a.event_id!=b.event_id AND (a.event_id=? OR b.event_id=?)";
                PreparedStatement statement = getPreparedStatement(sql);
                for (int eventId : eventIds) {
                    if (eventId == -1)
                        continue;
                    statement.setInt(1, eventId);
                    statement.setInt(2, eventId);
                    statement.executeUpdate();
                }
            }
        }
        connection.commit();
    }

    private static JHVEvent parseJSON(JsonEvent jsonEvent, boolean full) throws Exception {
        try (InputStream bais = new ByteArrayInputStream(jsonEvent.json); InputStream is = new GZIPInputStream(bais)) {
            return jsonEvent.type.source().handler().parseEventJSON(JSONUtils.get(is), jsonEvent.type, jsonEvent.id, jsonEvent.start, jsonEvent.end, full);
        }
    }

    private static List<JHVEvent> parseJSON(List<JsonEvent> jsonEvents, boolean full) {
        HashSet<Integer> ids = new HashSet<>();
        List<JHVEvent> events = new ArrayList<>();
        for (int i = 0; i < jsonEvents.size(); i++) {
            JsonEvent jsonEvent = jsonEvents.get(i);
            jsonEvents.set(i, null);
            if (!ids.add(jsonEvent.id))
                continue;

            try {
                events.add(parseJSON(jsonEvent, full));
            } catch (Exception e) {
                Log.error(e);
            }
        }
        return events;
    }

    public static List<JHVEvent> getRelatedEvents(int id, SWEKSupplier supplier) throws Exception {
        return parseJSON(executor.invokeAndWait(() -> collectRelationEvents(id, supplier)), true);
    }

    private static List<JsonEvent> collectRelationEvents(int id, SWEKSupplier type) {
        SWEKGroup group = type.group();
        List<JsonEvent> jsonEvents = new ArrayList<>();

        for (SWEK.RelatedEvents re : SWEKCatalog.getRelatedEvents()) {
            if (re.group() == group) {
                for (SWEK.RelatedOn swon : re.relatedOnList()) {
                    addRelationEvents(jsonEvents, id, type, re.relatedWith(),
                            swon.parameterFrom(), swon.parameterWith(), true);
                }
            }

            if (re.relatedWith() == group) {
                for (SWEK.RelatedOn swon : re.relatedOnList()) {
                    addRelationEvents(jsonEvents, id, type, re.group(),
                            swon.parameterFrom(), swon.parameterWith(), false);
                }
            }
        }

        return jsonEvents;
    }

    private static void addRelationEvents(List<JsonEvent> jsonEvents, int id, SWEKSupplier eventType,
                                          SWEKGroup otherGroup, String leftParameter, String rightParameter,
                                          boolean eventTypeIsLeft) {
        for (SWEKSupplier supplier : SWEKCatalog.getSuppliers(otherGroup)) {
            if (supplier == eventType)
                continue;

            SWEKSupplier leftType = eventTypeIsLeft ? eventType : supplier;
            SWEKSupplier rightType = eventTypeIsLeft ? supplier : eventType;
            try {
                jsonEvents.addAll(queryRelationEvents(id, leftType, rightType, leftParameter, rightParameter));
            } catch (Exception e) {
                Log.error(e);
            }
        }
    }

    public static boolean addStoredInterval(long start, long end, SWEKSupplier type) {
        try {
            executor.invokeAndWait(new AddStoredInterval(start, end, type));
            return true;
        } catch (Exception e) {
            Log.error("Could not store event date range", e);
            return false;
        }
    }

    private record AddStoredInterval(long start, long end, SWEKSupplier type) implements Callable<Void> {
        @Override
        public Void call() throws Exception {
            RequestCache typedCache = getStoredIntervals(type);
            int typeId = findOrInsertEventTypeId(type);
            Connection connection = EventDatabaseThread.getConnection();
            typedCache.adaptRequestCache(start, end);
            try {
                PreparedStatement delete = getPreparedStatement(DELETE_DATERANGES);
                delete.setInt(1, typeId);
                delete.executeUpdate();

                PreparedStatement pstatement = getPreparedStatement(INSERT_DATERANGE);
                for (Interval interval : typedCache.getAllRequestIntervals()) {
                    pstatement.setInt(1, typeId);
                    pstatement.setLong(2, interval.start());
                    pstatement.setLong(3, interval.end());
                    pstatement.executeUpdate();
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                storedIntervals.remove(type);
                throw e;
            }
            return null;
        }
    }

    public static boolean isStored(long start, long end, SWEKSupplier type) {
        try {
            return executor.invokeAndWait(new IsStored(start, end, type));
        } catch (Exception e) {
            Log.error(e);
            return false;
        }
    }

    private record IsStored(long start, long end, SWEKSupplier type) implements Callable<Boolean> {
        @Override
        public Boolean call() throws Exception {
            for (Interval interval : getStoredIntervals(type).getAllRequestIntervals()) {
                if (interval.start() > start)
                    return false;
                if (interval.end() >= end)
                    return true;
            }
            return false;
        }
    }

    private static RequestCache getStoredIntervals(SWEKSupplier type) throws Exception {
        RequestCache typedCache = storedIntervals.get(type);
        if (typedCache != null)
            return typedCache;

        typedCache = new RequestCache();
        long last_timestamp = getLastEvent(type);
        long lastEvent = last_timestamp == Long.MIN_VALUE ? Long.MAX_VALUE : Math.min(System.currentTimeMillis(), last_timestamp);
        long invalidationDate = lastEvent - ONEWEEK * 2;
        storedIntervals.put(type, typedCache);

        int typeId = findOrInsertEventTypeId(type);
        if (typeId != -1) {
            PreparedStatement pstatement = getPreparedStatement(SELECT_DATERANGE);
            pstatement.setInt(1, typeId);
            try (ResultSet rs = pstatement.executeQuery()) {
                while (rs.next()) {
                    long beginDate = rs.getLong(1);
                    long endDate = Math.min(invalidationDate, rs.getLong(2));
                    if (beginDate < endDate)
                        typedCache.adaptRequestCache(beginDate, endDate);
                }
            }
        }
        return typedCache;
    }

    private static long getLastEvent(SWEKSupplier type) throws Exception {
        int typeId = findOrInsertEventTypeId(type);
        long last_timestamp = Long.MIN_VALUE;
        if (typeId != -1) {
            PreparedStatement pstatement = getPreparedStatement(SELECT_LAST_EVENT);
            pstatement.setInt(1, typeId);
            try (ResultSet rs = pstatement.executeQuery()) {
                if (rs.next()) {
                    last_timestamp = rs.getLong(1);
                }
            }
        }
        return last_timestamp;
    }

    private record JsonEvent(byte[] json, SWEKSupplier type, int id, long start, long end) {}

    public static List<JHVEvent> events2Program(long start, long end, SWEKSupplier type, List<SWEK.Param> params) {
        try {
            return executor.invokeAndWait(new Events2Program(start, end, type, params));
        } catch (Exception e) {
            Log.error(e);
        }
        return Collections.emptyList();
    }

    private record Events2Program(long start, long end, SWEKSupplier type, List<SWEK.Param> params)
            implements Callable<List<JHVEvent>> {
        @Override
        public List<JHVEvent> call() throws Exception {
            List<JHVEvent> eventList = new ArrayList<>();
            int typeId = findOrInsertEventTypeId(type);
            if (typeId != -1) {
                String join = "LEFT JOIN " + type.dbName() + " AS tp ON tp.event_id=e.id";
                StringBuilder filters = new StringBuilder();
                for (SWEK.Param p : params) {
                    filters.append("AND tp.").append(p.name()).append(p.operand().representation).append("? ");
                }
                String sqlt = "SELECT e.id, e.start, e.end, e.data FROM events AS e " + join +
                        " WHERE e.type_id=? AND e.start<=? AND e.end>=? " + filters + " order by e.start, e.end ";
                PreparedStatement pstatement = getPreparedStatement(sqlt);
                pstatement.setInt(1, typeId);
                pstatement.setLong(2, end);
                pstatement.setLong(3, start);
                int parameterIndex = 4;
                for (SWEK.Param param : params)
                    pstatement.setDouble(parameterIndex++, param.value());

                try (ResultSet rs = pstatement.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt(1);
                        long _start = rs.getLong(2);
                        long _end = rs.getLong(3);
                        byte[] json = rs.getBytes(4);
                        try {
                            eventList.add(parseJSON(new JsonEvent(json, type, id, _start, _end), false));
                        } catch (Exception e) {
                            Log.error(e);
                        }
                    }
                }
            }
            return eventList;
        }
    }

    public static List<JHVEvent.Link> associations2Program(long start, long end, SWEKSupplier type) {
        try {
            return executor.invokeAndWait(new Associations2Program(start, end, type));
        } catch (Exception e) {
            Log.error(e);
        }
        return Collections.emptyList();
    }

    private record Associations2Program(long start, long end, SWEKSupplier type)
            implements Callable<List<JHVEvent.Link>> {
        @Override
        public List<JHVEvent.Link> call() throws Exception {
            List<JHVEvent.Link> assocList = new ArrayList<>();
            int typeId = findOrInsertEventTypeId(type);
            if (typeId != -1) {
                PreparedStatement pstatement = getPreparedStatement(SELECT_ASSOCIATIONS);
                pstatement.setInt(1, typeId);
                pstatement.setLong(2, end);
                pstatement.setLong(3, start);
                pstatement.setInt(4, typeId);
                pstatement.setLong(5, end);
                pstatement.setLong(6, start);

                try (ResultSet rs = pstatement.executeQuery()) {
                    while (rs.next()) {
                        assocList.add(new JHVEvent.Link(rs.getInt(1), rs.getInt(2)));
                    }
                }
            }
            return assocList;
        }
    }

    private static List<JsonEvent> queryRelationEvents(int event_id, SWEKSupplier type_left, SWEKSupplier type_right, String param_left, String param_right) throws Exception {
        int type_left_id = findOrInsertEventTypeId(type_left);
        int type_right_id = findOrInsertEventTypeId(type_right);

        if (type_left_id != -1 && type_right_id != -1) {
            String table_left_name = type_left.dbName();
            String table_right_name = type_right.dbName();

            String sqlt = "SELECT tl.event_id, tr.event_id FROM " + table_left_name + " AS tl," + table_right_name + " AS tr" + " WHERE tl." + param_left + "=tr." + param_right + " AND tl.event_id!=tr.event_id AND (tl.event_id=? OR tr.event_id=?)";
            PreparedStatement pstatement = getPreparedStatement(sqlt);
            pstatement.setLong(1, event_id);
            pstatement.setLong(2, event_id);

            StringBuilder idList = new StringBuilder();
            try (ResultSet rs = pstatement.executeQuery()) {
                boolean next = rs.next();
                while (next) {
                    idList.append(rs.getInt(1)).append(',').append(rs.getInt(2));
                    next = rs.next();
                    if (next) {
                        idList.append(',');
                    }
                }
            }
            if (idList.isEmpty())
                return Collections.emptyList();

            String query = "SELECT distinct events.id, events.start, events.end, events.data, event_type.supplier FROM events LEFT JOIN event_type ON events.type_id = event_type.id WHERE events.id IN ( " + idList + ") AND events.id != " + event_id;
            List<JsonEvent> ret = new ArrayList<>();
            try (Statement statement = pstatement.getConnection().createStatement();
                 ResultSet rs = statement.executeQuery(query)) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    long start = rs.getLong(2);
                    long end = rs.getLong(3);
                    byte[] json = rs.getBytes(4);
                    ret.add(new JsonEvent(json, SWEKCatalog.getSupplier(rs.getString(5)), id, start, end));
                }
            }
            return ret;
        }
        return Collections.emptyList();
    }

}
